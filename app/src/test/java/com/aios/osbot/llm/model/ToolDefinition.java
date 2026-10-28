package com.aios.osbot.llm.model;

/** Public contract fixture, not copied host code. */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Object parameters;

    public ToolDefinition(String name, String description, Object parameters) {
        this.name = name; this.description = description; this.parameters = parameters;
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Object getParameters() { return parameters; }
}
