package io.fifthnn.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int fifthnn$getClickCount();

    @Accessor("clickCount")
    void fifthnn$setClickCount(int clickCount);

    @Accessor("key")
    InputConstants.Key fifthnn$getKey();
}
