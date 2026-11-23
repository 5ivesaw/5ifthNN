"""
5ifthNN Official Discord Server Manager Bot (v1.1 — Minimal)
Ultra-lightweight (~20MB RAM, <0.2% CPU), zero-emoji, always-online manager for VPS deployment.

Features:
  - Auto-assigns Community role + minimal welcome on member join
  - Auto-tracks /5nn share (.zip / .5nn.gz / .5nn) uploads in submit-datasets,
    logs stats to SQLite, and auto-grants Data Recorder (1+) & Top Contributor (5+ or >=10MB)
  - Minimal slash commands: /help, /setup, /leaderboard, /status, /lock, /unlock, /purge, /announce
"""

from __future__ import annotations

import logging
import os
import resource
import sqlite3
import time
from pathlib import Path
from typing import Optional

import discord
from discord import app_commands
from discord.ext import commands

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


class FifthNNBot(commands.Bot):
    def __init__(self) -> None:
        intents = discord.Intents.none()
        intents.guilds = True
        intents.members = True
        intents.messages = True
        intents.message_content = True

        super().__init__(
            command_prefix="!",
            intents=intents,
            help_command=None,
            max_messages=100,
            member_cache_flags=discord.MemberCacheFlags.from_intents(intents),
        )
        self.db = BotDB(DB_PATH)
        self.start_time = time.time()

    async def setup_hook(self) -> None:
        guild_obj = discord.Object(id=GUILD_ID)
        self.tree.copy_global_to(guild=guild_obj)
        synced = await self.tree.sync(guild=guild_obj)
        log.info("Synced %d slash commands to guild %d", len(synced), GUILD_ID)


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


@bot.event
async def on_ready() -> None:
    log.info("Logged in as %s (id=%s)", bot.user, bot.user.id if bot.user else "?")
    await bot.change_presence(
        status=discord.Status.online,
        activity=discord.Activity(type=discord.ActivityType.watching, name="5ifthNN • /help"),
    )


@bot.event
async def on_member_join(member: discord.Member) -> None:
    if member.guild.id != GUILD_ID or member.bot:
        return

    comm_role = _find_role(member.guild, ROLE_COMMUNITY)
    if comm_role:
        try:
            await member.add_roles(comm_role, reason="Auto role on join")
        except discord.HTTPException:
            pass

    general = _find_channel_contains(member.guild, "general")
    setup_ch = _find_channel_contains(member.guild, "setup")
    if general:
        setup_ref = setup_ch.mention if setup_ch else "`/setup`"
        emb = discord.Embed(
            title="✦ Member Joined",
            description=f"Welcome {member.mention} to **5ifthNN**.\n▸ See {setup_ref} or run `/help` to get started.",
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
            emb = discord.Embed(
                title="✦ Dataset Recorded",
                description=(
                    f"Thank you {message.author.mention} — logged {len(valid_files)} file(s).\n"
                    f"▸ Total: **{subs}** upload(s) │ **{_fmt_mb(total_bytes)}**{role_note}"
                ),
                color=COLOR_SUCCESS,
            )
            try:
                await message.reply(embed=emb, mention_author=False)
            except discord.HTTPException:
                pass


@bot.tree.command(name="help", description="View 5ifthNN commands and links")
async def cmd_help(interaction: discord.Interaction) -> None:
    emb = discord.Embed(
        title="✦ 5ifthNN Commands",
        description=f"**GitHub:** {GITHUB_URL}",
        color=COLOR_PRIMARY,
    )
    emb.add_field(
        name="▸ Public",
        value="`/setup` • `/leaderboard` • `/status`",
        inline=False,
    )
    emb.add_field(
        name="▸ Staff",
        value="`/lock` • `/unlock` • `/purge <count>` • `/announce <title> <msg>`",
        inline=False,
    )
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="setup", description="60-second 5ifthNN MC 26.3 Fabric install guide")
async def cmd_setup(interaction: discord.Interaction) -> None:
    emb = discord.Embed(
        title="✦ 5ifthNN — Install & Controls (MC 26.3 Fabric)",
        description=(
            "1. Install **Fabric API** (`0.166.2+26.3`), **ViaFabricPlus** (`5.1.3+`), and **5ifthNN** (`~184 KB`).\n"
            "2. Join BedWars — auto-recording starts/stops automatically.\n"
            "3. `F8` Control Panel │ `F6` Highlight 8s │ `F7` Scrap 3s/10s │ `/5nn share` Export ZIP."
        ),
        color=COLOR_SUCCESS,
    )
    emb.set_footer(text=GITHUB_URL)
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="leaderboard", description="Top 5ifthNN dataset contributors")
async def cmd_leaderboard(interaction: discord.Interaction) -> None:
    rows = bot.db.top_contributors(10)
    if not rows:
        await interaction.response.send_message("No dataset contributions logged yet.", ephemeral=True)
        return
    lines = [f"`{i:02d}` │ <@{uid}> — **{subs}** upload(s) │ `{_fmt_mb(b)}`" for i, (uid, subs, b) in enumerate(rows, 1)]
    total_users, total_subs, total_bytes = bot.db.totals()
    emb = discord.Embed(title="✦ Dataset Leaderboard", description="\n".join(lines), color=COLOR_WARN)
    emb.set_footer(text=f"{total_users} contributors • {total_subs} uploads • {_fmt_mb(total_bytes)}")
    await interaction.response.send_message(embed=emb)


@bot.tree.command(name="status", description="Live bot health, RAM usage, and dataset totals")
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
    emb.add_field(name="▸ Uploads", value=f"`{total_subs}` ({total_users} users)", inline=True)
    emb.add_field(name="▸ Volume", value=f"`{_fmt_mb(total_bytes)}`", inline=True)
    await interaction.response.send_message(embed=emb, ephemeral=True)


@bot.tree.command(name="lock", description="Lock a text channel for @everyone")
@app_commands.checks.has_permissions(manage_channels=True)
async def cmd_lock(interaction: discord.Interaction, channel: Optional[discord.TextChannel] = None) -> None:
    ch = channel or interaction.channel
    if not isinstance(ch, discord.TextChannel) or not interaction.guild:
        await interaction.response.send_message("Target must be a text channel.", ephemeral=True)
        return
    ow = ch.overwrites_for(interaction.guild.default_role)
    ow.send_messages = False
    ow.create_public_threads = False
    await ch.set_permissions(interaction.guild.default_role, overwrite=ow, reason="Locked by staff")
    await interaction.response.send_message(f"▸ Locked {ch.mention}.")


@bot.tree.command(name="unlock", description="Unlock a text channel for @everyone")
@app_commands.checks.has_permissions(manage_channels=True)
async def cmd_unlock(interaction: discord.Interaction, channel: Optional[discord.TextChannel] = None) -> None:
    ch = channel or interaction.channel
    if not isinstance(ch, discord.TextChannel) or not interaction.guild:
        await interaction.response.send_message("Target must be a text channel.", ephemeral=True)
        return
    ow = ch.overwrites_for(interaction.guild.default_role)
    ow.send_messages = None
    ow.create_public_threads = None
    await ch.set_permissions(interaction.guild.default_role, overwrite=ow, reason="Unlocked by staff")
    await interaction.response.send_message(f"▸ Unlocked {ch.mention}.")


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
async def cmd_announce(interaction: discord.Interaction, title: str, message: str) -> None:
    target = _find_channel_contains(interaction.guild, "announcements") if interaction.guild else None
    if not target:
        await interaction.response.send_message("Could not find announcements channel.", ephemeral=True)
        return
    emb = discord.Embed(title=f"✦ {title}", description=message.replace("\\n", "\n"), color=COLOR_DANGER)
    emb.set_footer(text=f"Posted by {interaction.user.display_name} • 5ifthNN")
    await target.send(embed=emb)
    await interaction.response.send_message(f"▸ Announcement posted in {target.mention}.", ephemeral=True)


@bot.tree.error
async def on_app_command_error(interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
    msg = "▸ Missing permission." if isinstance(error, app_commands.MissingPermissions) else f"▸ Error: {error}"
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
