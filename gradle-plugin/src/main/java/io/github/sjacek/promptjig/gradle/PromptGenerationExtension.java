package io.github.sjacek.promptjig.gradle;

import org.gradle.api.provider.Property;

/** Java generation settings, independent of model transport and Spring AI runtime options. */
public abstract class PromptGenerationExtension {
    /** Creates managed generation settings with conventions supplied by the plugin. */
    public PromptGenerationExtension() { }

    /**
     * Controls automatic Java main source wiring, not explicit invocation of the generation task.
     * The plugin reads this setting after project evaluation so late DSL configuration is honored.
     * @return false by default; a {@code generate {}} block sets it to true before executing the block
     */
    public abstract Property<Boolean> getEnabled();

    /** {@return Java package shared by generated contract constants and records; defaults to io.github.sjacek.promptjig.generated} */
    public abstract Property<String> getPackageName();

    /** {@return Jackson target family, v2 (default) or v3; dependencies remain owned by the consumer} */
    public abstract Property<String> getJackson();

    /** {@return classpath location of the configured source root; defaults to prompts} */
    public abstract Property<String> getResourcePrefix();
}
