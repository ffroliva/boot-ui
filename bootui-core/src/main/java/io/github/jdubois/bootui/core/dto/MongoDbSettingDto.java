package io.github.jdubois.bootui.core.dto;

/** An allow-listed local or observed setting, never an arbitrary driver option. */
public record MongoDbSettingDto(String name, String value, String provenance, String source) {}
