"""
5ifthNN Official Discord Server Manager Bot (v1.0)
Ultra-lightweight (~20MB RAM, <0.2% CPU), zero-emoji, always-online manager for VPS deployment.

Features:
  - Auto-assigns Community role + tracks invite attribution on member join
  - Auto-detects /5nn share (.zip / .5nn.gz / .5nn) uploads in submit-datasets,
    logs stats to SQLite, and auto-promotes Data Recorder (1+) & Top Contributor (5+ or >=10MB)
  - Full slash-command suite: /help, /setup, /submit, /leaderboard, /profile, /invites, /status
  - Admin controls: /lock, /unlock, /slowmode, /purge, /announce, /credit, /sync-roles
"""

from __future__ import annotations

import logging
import os
import resource
import sqlite3
import time
from pathlib import Path
from typing import Dict, Optional

import discord
from discord import app_commands
from discord.ext import commands

# ---------------------------------------------------------------------------
# Configuration (read from environment or local .env file — zero extra deps!)
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent


def _load_dotenv(env_path: Path) -> None:
    if not env_path.exists():
        return
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_dotenv(BASE_DIR / ".env")

TOKEN = os.environ.get("DISCORD_TOKEN", "").strip()
GUILD_ID = int(os.environ.get("GUILD_ID", "1574218726956343448"))
GITHUB_URL = os.environ.get("GITHUB_URL", "https://github.com/5ivesaw/5ifthNN")
DB_PATH = Path(os.environ.get("DB_PATH", str(BASE_DIR / "bot_data.db")))

ROLE_COMMUNITY = "Community"
ROLE_RECORDER = "● Data Recorder"
ROLE_TOP_CONTRIBUTOR = "★ Top Contributor"

COLOR_PRIMARY = 0x38BDF8
COLOR_SUCCESS = 0x4ADE80
COLOR_WARN = 0xFACC15
COLOR_DANGER = 0xF43F5E

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("5ifthNN-Bot")


# ---------------------------------------------------------------------------
# Lightweight SQLite Storage (stdlib, zero RAM overhead)
# ---------------------------------------------------------------------------
class BotDB:
    def __init__(self, path: Path) -> None:
        self.conn = sqlite3.connect(path)
        self.conn.execute("PRAGMA journal_mode=WAL;")
        self.conn.execute("PRAGMA synchronous=NORMAL;")
        self.conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS contributors (
                user_id     INTEGER PRIMARY KEY,
                submissions INTEGER NOT NULL DEFAULT 0,
                total_bytes INTEGER NOT NULL DEFAULT 0,
                last_ts     INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS invites (
                user_id     INTEGER PRIMARY KEY,
                joins       INTEGER NOT NULL DEFAULT 0
            );
            """
        )
        self.conn.commit()

    def add_contribution(self, user_id: int, count: int, size_bytes: int) -> tuple[int, int]:
        now = int(time.time())
        self.conn.execute(
            """
            INSERT INTO contributors (user_id, submissions, total_bytes, last_ts)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                submissions = submissions + excluded.submissions,
                total_bytes = total_bytes + excluded.total_bytes,
                last_ts     = excluded.last_ts
            """,
            (user_id, count, size_bytes, now),
        )
        self.conn.commit()
        row = self.conn.execute(
            "SELECT submissions, total_bytes FROM contributors WHERE user_id = ?", (user_id,)
        ).fetchone()
        return (int(row[0]), int(row[1])) if row else (count, size_bytes)

    def get_contributor(self, user_id: int) -> tuple[int, int]:
        row = self.conn.execute(
            "SELECT submissions, total_bytes FROM contributors WHERE user_id = ?", (user_id,)
        ).fetchone()
        return (int(row[0]), int(row[1])) if row else (0, 0)

    def top_contributors(self, limit: int = 10) -> list[tuple[int, int, int]]:
        return self.conn.execute(
            "SELECT user_id, submissions, total_bytes FROM contributors "
            "ORDER BY submissions DESC, total_bytes DESC LIMIT ?",
            (limit,),
        ).fetchall()

    def totals(self) -> tuple[int, int, int]:
        row = self.conn.execute(
            "SELECT COUNT(*), COALESCE(SUM(submissions), 0), COALESCE(SUM(total_bytes), 0) FROM contributors"
        ).fetchone()
        return int(row[0]), int(row[1]), int(row[2])

    def add_invite(self, inviter_id: int) -> int:
        self.conn.execute(
            """
            INSERT INTO invites (user_id, joins) VALUES (?, 1)
            ON CONFLICT(user_id) DO UPDATE SET joins = joins + 1
            """,
            (inviter_id,),
        )
        self.conn.commit()
        return self.get_invites(inviter_id)

    def get_invites(self, user_id: int) -> int:
        row = self.conn.execute("SELECT joins FROM invites WHERE user_id = ?", (user_id,)).fetchone()
        return int(row[0]) if row else 0


# ---------------------------------------------------------------------------
# Ultra-Optimized Bot Client
# ---------------------------------------------------------------------------
class FifthNNBot(commands.Bot):
    def __init__(self) -> None:
        intents = discord.Intents.none()
        intents.guilds = True
        intents.members = True
        intents.messages = True
        intents.message_content = True
        intents.invites = True

        super().__init__(
            command_prefix="!",
            intents=intents,
            help_command=None,
            max_messages=100,  # strict tiny cache to keep VPS RAM ~20MB
            member_cache_flags=discord.MemberCacheFlags.from_intents(intents),
        )
        self.db = BotDB(DB_PATH)
        self.start_time = time.time()
        self.invite_uses: Dict[str, int] = {}

    async def setup_hook(self) -> None:
        guild_obj = discord.Object(id=GUILD_ID)
        self.tree.copy_global_to(guild=guild_obj)
        synced = await self.tree.sync(guild=guild_obj)
        log.info("Synced %d slash commands to guild %d", len(synced), GUILD_ID)

    async def refresh_invites(self, guild: discord.Guild) -> None:
        try:
            invs = await guild.invites()
            self.invite_uses = {inv.code: (inv.uses or 0) for inv in invs}
        except discord.Forbidden:
            log.warning("Missing Manage Server permission to read invites.")
        except Exception as exc:
            log.warning("Invite cache refresh failed: %s", exc)


bot = FifthNNBot()


def _find_role(guild: discord.Guild, name: str) -> Optional[discord.Role]:
    return discord.utils.get(guild.roles, name=name)


def _find_channel_contains(guild: discord.Guild, keyword: str) -> Optional[discord.TextChannel]:
    kw = keyword.lower()
    for ch in guild.text_channels:
        if kw in ch.name.lower():
            return ch
    return None


async def _sync_contributor_roles(member: discord.Member, subs: int, total_bytes: int) -> list[str]:
    granted: list[str] = []
    if subs >= 1:
        r = _find_role(member.guild, ROLE_RECORDER)
        if r and r not in member.roles:
            try:
                await member.add_roles(r, reason="5ifthNN dataset contribution")
                granted.append(r.name)
            except discord.HTTPException:
                pass
    if subs >= 5 or total_bytes >= 10 * 1024 * 1024:
        r = _find_role(member.guild, ROLE_TOP_CONTRIBUTOR)
        if r and r not in member.roles:
            try:
                await member.add_roles(r, reason="5ifthNN top contributor milestone")
                granted.append(r.name)
            except discord.HTTPException:
                pass
    return granted


def _fmt_mb(num_bytes: int) -> str:
    return f"{num_bytes / (1024.0 * 1024.0):.2f} MB"


# ---------------------------------------------------------------------------
# Events: Ready, Invites, Member Joins, Automatic Dataset Detection
# ---------------------------------------------------------------------------
@bot.event
async def on_ready() -> None:
    log.info("Logged in as %s (id=%s)", bot.user, bot.user.id if bot.user else "?")
    await bot.change_presence(
        status=discord.Status.online,
        activity=discord.Activity(type=discord.ActivityType.watching, name="5ifthNN • /help"),
    )
    guild = bot.get_guild(GUILD_ID)
    if guild:
        await bot.refresh_invites(guild)


@bot.event
async def on_invite_create(invite: discord.Invite) -> None:
    if invite.guild and invite.guild.id == GUILD_ID:
        bot.invite_uses[invite.code] = invite.uses or 0


@bot.event
async def on_invite_delete(invite: discord.Invite) -> None:
    bot.invite_uses.pop(invite.code, None)


@bot.event
async def on_member_join(member: discord.Member) -> None:
    if member.guild.id != GUILD_ID or member.bot:
        return

    # 1. Auto-assign Community role
    comm_role = _find_role(member.guild, ROLE_COMMUNITY)
    if comm_role:
        try:
            await member.add_roles(comm_role, reason="Auto role on join")
        except discord.HTTPException:
            pass

    # 2. Attribute invite
    inviter_text = ""
    try:
        new_invs = await member.guild.invites()
        for inv in new_invs:
            prev = bot.invite_uses.get(inv.code, 0)
            cur = inv.uses or 0
            if cur > prev and inv.inviter and not inv.inviter.bot:
                total = bot.db.add_invite(inv.inviter.id)
                inviter_text = f"\n▸ Invited by {inv.inviter.mention} (`{total}` total)"
                break
        bot.invite_uses = {inv.code: (inv.uses or 0) for inv in new_invs}
    except Exception:
        pass

    # 3. Clean minimal welcome in general
    general = _find_channel_contains(member.guild, "general")
    setup_ch = _find_channel_contains(member.guild, "downloads-and-setup")
    if general:
        setup_ref = setup_ch.mention if setup_ch else "`/setup`"
        emb = discord.Embed(
            title="✦ Member Joined",
            description=(
                f"Welcome {member.mention} to **5ifthNN**.\n"
                f"▸ Start with {setup_ref} or run `/help` to view guides and commands.{inviter_text}"
            ),
            color=COLOR_PRIMARY,
        )
        emb.set_footer(text=f"Member #{member.guild.member_count}")
        try:
            await general.send(embed=emb)
        except discord.HTTPException:
            pass


@bot.event
async def on_message(message: discord.Message) -> None:
    if message.author.bot or not message.guild or message.guild.id != GUILD_ID:
        return

    # Auto-track dataset uploads in submit-datasets channel
    if "submit-datasets" in message.channel.name.lower() and message.attachments:
        valid_files = [
            att for att in message.attachments
            if att.filename.lower().endswith((".zip", ".5nn.gz", ".5nn"))
        ]
        if valid_files and isinstance(message.author, discord.Member):
            total_sz = sum(a.size for a in valid_files)
            subs, total_bytes = bot.db.add_contribution(message.author.id, len(valid_files), total_sz)
            granted = await _sync_contributor_roles(message.author, subs, total_bytes)
            role_note = f"\n▸ Role unlocked: **{', '.join(granted)}**" if granted else ""
            names = ", ".join(f"`{a.filename}`" for a in valid_files[:3])
            emb = discord.Embed(
                title="✦ Dataset Recorded",
                description=(
                    f"Thank you {message.author.mention} — logged {len(valid_files)} file(s): {names}\n"
                    f"▸ Your total: **{subs}** submission(s) │ **{_fmt_mb(total_bytes)}**{role_note}"
                ),
                color=COLOR_SUCCESS,
            )
            try:
                await message.reply(embed=emb, mention_author=False)
            except discord.HTTPException:
                pass


# ---------------------------------------------------------------------------
# Public Slash Commands (Guides, Stats, Leaderboards)
# ---------------------------------------------------------------------------
@bot.tree.command(name="help", description="View 5ifthNN commands and server guide")
async def cmd_help(interaction: discord.Interaction) -> None:
    emb = discord.Embed(
        title="✦ 5ifthNN Server Guide & Commands",
        description=f"Official Pure Neural Network BedWars AI community.\n**GitHub:** {GITHUB_URL}",
        color=COLOR_PRIMARY,
    )
    emb.add_field(
        name="▸ Guides & Community",
        value=(
            "`/setup` — 60-second MC 26.3 Fabric install guide\n"
            "`/submit` — How to export & upload `/5nn share` datasets\n"
            "`/leaderboard` — Top dataset contributors\n"
            "`/profile [member]` — View contributions & invites\n"
            "`/invites [member]` — Check invite count\n"
            "`/status` — Bot & dataset statistics"
        ),
        inline=False,
    )
    emb.add_field(
        name="▸ Staff & Moderation",
        value=(
            "`/lock` │ `/unlock` — Lock or unlock a channel\n"
            "`/slowmode <sec>` — Set channel slowmode\n"
            "`/purge <count>` — Delete 1–100 recent messages\n"
            "`/announce <title> <msg>` — Post a formatted announcement\n"
            "`/credit <member> <count> [mb]` — Manually credit a contributor\n"
            "`/sync-roles` — Sync Community & contributor roles"
        ),
        inline=False,
    )
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="setup", description="60-second 5ifthNN MC 26.3 Fabric install guide")
async def cmd_setup(interaction: discord.Interaction) -> None:
    emb = discord.Embed(
        title="✦ 5ifthNN — Installation & Controls (MC 26.3 Fabric)",
        color=COLOR_SUCCESS,
    )
    emb.add_field(
        name="01 │ Required Mods",
        value=(
            "• Minecraft **26.3** + **Fabric Loader 0.20.1+**\n"
            "• **Fabric API** (`0.166.2+26.3`)\n"
            "• **ViaFabricPlus** (`5.1.3+` — auto-configured to **1.8.x**)\n"
            "• **5ifthNN** mod JAR (`~184 KB` from GitHub)"
        ),
        inline=False,
    )
    emb.add_field(
        name="02 │ In-Game Controls",
        value=(
            "`F8` — 3-Tab Control Panel (**Recorder │ Helpers │ Phase 2 AI**)\n"
            "`F6` — Highlight last 8s (`2.5×` BC weight)\n"
            "`F7` — Scrap last 3s (double-tap = `10s`)\n"
            "`F9` — Manual Start / Stop\n"
            "`/5nn share` — Export all Grade A/B matches into 1 `.zip`"
        ),
        inline=False,
    )
    emb.set_footer(text=GITHUB_URL)
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="submit", description="How to export and submit BedWars datasets")
async def cmd_submit(interaction: discord.Interaction) -> None:
    emb = discord.Embed(
        title="✦ How to Submit BedWars Datasets",
        description=(
            "1. Play BedWars with **5ifthNN** + **ViaFabricPlus (1.8.x)**.\n"
            "2. Run `/5nn share` in chat (or click **Export Share ZIP** in `F8 → Recorder`).\n"
            "3. Upload the `.zip` from `.minecraft/config/5ifthnn/` in `│・submit-datasets`.\n\n"
            "▸ **1+ submission** unlocks **● Data Recorder**\n"
            "▸ **5+ submissions** (or `10+ MB`) unlocks **★ Top Contributor**"
        ),
        color=COLOR_WARN,
    )
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="leaderboard", description="Top 5ifthNN dataset contributors")
async def cmd_leaderboard(interaction: discord.Interaction) -> None:
    rows = bot.db.top_contributors(10)
    if not rows:
        await interaction.response.send_message("No dataset contributions logged yet.", ephemeral=True)
        return
    lines = []
    for idx, (uid, subs, b) in enumerate(rows, start=1):
        lines.append(f"`{idx:02d}` │ <@{uid}> — **{subs}** upload(s) │ `{_fmt_mb(b)}`")
    total_users, total_subs, total_bytes = bot.db.totals()
    emb = discord.Embed(
        title="✦ Dataset Contributor Leaderboard",
        description="\n".join(lines),
        color=COLOR_WARN,
    )
    emb.set_footer(text=f"Total: {total_users} contributors • {total_subs} uploads • {_fmt_mb(total_bytes)}")
    await interaction.response.send_message(embed=emb)


@bot.tree.command(name="profile", description="View dataset contributions and invites for a member")
async def cmd_profile(interaction: discord.Interaction, member: Optional[discord.Member] = None) -> None:
    target = member or interaction.user
    subs, total_bytes = bot.db.get_contributor(target.id)
    invs = bot.db.get_invites(target.id)
    emb = discord.Embed(title=f"✦ Profile — {target.display_name}", color=COLOR_PRIMARY)
    emb.add_field(name="▸ Submissions", value=f"`{subs}`", inline=True)
    emb.add_field(name="▸ Dataset Volume", value=f"`{_fmt_mb(total_bytes)}`", inline=True)
    emb.add_field(name="▸ Invites", value=f"`{invs}`", inline=True)
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="invites", description="Check invite count for a member")
async def cmd_invites(interaction: discord.Interaction, member: Optional[discord.Member] = None) -> None:
    target = member or interaction.user
    count = bot.db.get_invites(target.id)
    await interaction.response.send_message(
        f"▸ {target.mention} has **{count}** tracked invite(s).", ephemeral=True
    )


@bot.tree.command(name="status", description="Live bot health, RAM usage, and server totals")
async def cmd_status(interaction: discord.Interaction) -> None:
    uptime_s = int(time.time() - bot.start_time)
    hrs, rem = divmod(uptime_s, 3600)
    mins, secs = divmod(rem, 60)
    rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    total_users, total_subs, total_bytes = bot.db.totals()
    members = interaction.guild.member_count if interaction.guild else 0

    emb = discord.Embed(title="✦ 5ifthNN Bot Status", color=COLOR_PRIMARY)
    emb.add_field(name="▸ Uptime", value=f"`{hrs}h {mins}m {secs}s`", inline=True)
    emb.add_field(name="▸ Latency", value=f"`{bot.latency * 1000:.0f} ms`", inline=True)
    emb.add_field(name="▸ Peak RAM", value=f"`{rss_mb:.1f} MB`", inline=True)
    emb.add_field(name="▸ Members", value=f"`{members}`", inline=True)
    emb.add_field(name="▸ Dataset Uploads", value=f"`{total_subs}` ({total_users} users)", inline=True)
    emb.add_field(name="▸ Dataset Volume", value=f"`{_fmt_mb(total_bytes)}`", inline=True)
    await interaction.response.send_message(embed=emb, ephemeral=True)


# ---------------------------------------------------------------------------
# Admin / Server Manager Slash Commands
# ---------------------------------------------------------------------------
@bot.tree.command(name="lock", description="Lock a text channel for @everyone")
@app_commands.checks.has_permissions(manage_channels=True)
async def cmd_lock(
    interaction: discord.Interaction,
    channel: Optional[discord.TextChannel] = None,
    reason: str = "Channel locked by staff.",
) -> None:
    ch = channel or interaction.channel
    if not isinstance(ch, discord.TextChannel) or not interaction.guild:
        await interaction.response.send_message("Target must be a text channel.", ephemeral=True)
        return
    ow = ch.overwrites_for(interaction.guild.default_role)
    ow.send_messages = False
    ow.create_public_threads = False
    await ch.set_permissions(interaction.guild.default_role, overwrite=ow, reason=reason)
    await interaction.response.send_message(f"▸ Locked {ch.mention} — {reason}")


@bot.tree.command(name="unlock", description="Unlock a text channel for @everyone")
@app_commands.checks.has_permissions(manage_channels=True)
async def cmd_unlock(
    interaction: discord.Interaction,
    channel: Optional[discord.TextChannel] = None,
) -> None:
    ch = channel or interaction.channel
    if not isinstance(ch, discord.TextChannel) or not interaction.guild:
        await interaction.response.send_message("Target must be a text channel.", ephemeral=True)
        return
    ow = ch.overwrites_for(interaction.guild.default_role)
    ow.send_messages = None
    ow.create_public_threads = None
    await ch.set_permissions(interaction.guild.default_role, overwrite=ow, reason="Unlocked by staff")
    await interaction.response.send_message(f"▸ Unlocked {ch.mention}.")


@bot.tree.command(name="slowmode", description="Set slowmode delay in seconds (0 to disable)")
@app_commands.checks.has_permissions(manage_channels=True)
async def cmd_slowmode(
    interaction: discord.Interaction,
    seconds: app_commands.Range[int, 0, 21600],
    channel: Optional[discord.TextChannel] = None,
) -> None:
    ch = channel or interaction.channel
    if not isinstance(ch, discord.TextChannel):
        await interaction.response.send_message("Target must be a text channel.", ephemeral=True)
        return
    await ch.edit(slowmode_delay=seconds)
    msg = f"▸ Disabled slowmode in {ch.mention}." if seconds == 0 else f"▸ Set slowmode in {ch.mention} to `{seconds}s`."
    await interaction.response.send_message(msg, ephemeral=True)


@bot.tree.command(name="purge", description="Bulk-delete 1 to 100 recent messages")
@app_commands.checks.has_permissions(manage_messages=True)
async def cmd_purge(interaction: discord.Interaction, amount: app_commands.Range[int, 1, 100]) -> None:
    if not isinstance(interaction.channel, discord.TextChannel):
        await interaction.response.send_message("Must be used in a text channel.", ephemeral=True)
        return
    await interaction.response.defer(ephemeral=True)
    deleted = await interaction.channel.purge(limit=amount)
    await interaction.followup.send(f"▸ Deleted `{len(deleted)}` message(s).", ephemeral=True)


@bot.tree.command(name="announce", description="Post a clean formatted announcement embed")
@app_commands.checks.has_permissions(manage_guild=True)
async def cmd_announce(
    interaction: discord.Interaction,
    title: str,
    message: str,
    channel: Optional[discord.TextChannel] = None,
) -> None:
    target = channel or (_find_channel_contains(interaction.guild, "announcements") if interaction.guild else None)
    if not target:
        await interaction.response.send_message("Could not find announcements channel.", ephemeral=True)
        return
    emb = discord.Embed(title=f"✦ {title}", description=message.replace("\\n", "\n"), color=COLOR_DANGER)
    emb.set_footer(text=f"Posted by {interaction.user.display_name} • 5ifthNN")
    await target.send(embed=emb)
    await interaction.response.send_message(f"▸ Announcement posted in {target.mention}.", ephemeral=True)


@bot.tree.command(name="credit", description="Manually credit a member's dataset contribution and sync roles")
@app_commands.checks.has_permissions(manage_roles=True)
async def cmd_credit(
    interaction: discord.Interaction,
    member: discord.Member,
    submissions: app_commands.Range[int, 1, 500] = 1,
    mb: float = 0.5,
) -> None:
    size_bytes = max(0, int(mb * 1024 * 1024))
    subs, total_bytes = bot.db.add_contribution(member.id, submissions, size_bytes)
    granted = await _sync_contributor_roles(member, subs, total_bytes)
    role_note = f" │ Roles added: **{', '.join(granted)}**" if granted else ""
    await interaction.response.send_message(
        f"▸ Credited {member.mention} (`+{submissions}` / `+{mb:.2f} MB`). Total: **{subs}** │ `{_fmt_mb(total_bytes)}`{role_note}"
    )


@bot.tree.command(name="sync-roles", description="Ensure all non-bot members have Community and earned contributor roles")
@app_commands.checks.has_permissions(administrator=True)
async def cmd_sync_roles(interaction: discord.Interaction) -> None:
    if not interaction.guild:
        return
    await interaction.response.defer(ephemeral=True)
    comm = _find_role(interaction.guild, ROLE_COMMUNITY)
    updated = 0
    for m in interaction.guild.members:
        if m.bot:
            continue
        if comm and comm not in m.roles:
            try:
                await m.add_roles(comm, reason="Role sync")
                updated += 1
            except discord.HTTPException:
                pass
        subs, total_bytes = bot.db.get_contributor(m.id)
        if subs > 0:
            granted = await _sync_contributor_roles(m, subs, total_bytes)
            if granted:
                updated += 1
    await interaction.followup.send(f"▸ Role sync complete (`{updated}` updates applied).", ephemeral=True)


@bot.tree.error
async def on_app_command_error(interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
    if isinstance(error, app_commands.MissingPermissions):
        msg = "▸ You do not have permission to run this command."
    else:
        log.error("Slash command error: %s", error)
        msg = f"▸ Command failed: {error}"
    if interaction.response.is_done():
        await interaction.followup.send(msg, ephemeral=True)
    else:
        await interaction.response.send_message(msg, ephemeral=True)


def main() -> None:
    if not TOKEN:
        raise SystemExit("ERROR: DISCORD_TOKEN is not set. Put it in .env or environment variables.")
    bot.run(TOKEN, log_handler=None)


if __name__ == "__main__":
    main()
