package io.github.jdubois.bootui.core.dto;

/** Driver knowledge, not a fresh health or authentication check. */
public record MongoDbServerDto(String endpoint, String type, String state) {}
