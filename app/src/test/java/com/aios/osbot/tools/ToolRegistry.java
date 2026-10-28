package com.aios.osbot.tools;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Deterministic registry fixture covering only registration, identity and eviction. */
public final class ToolRegistry {
    public static ToolRegistry instance = new ToolRegistry();
    public final Set<String> systemToolNames = new LinkedHashSet<>();
    public final Set<String> hiddenNames = new LinkedHashSet<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    public int registrations;

    public Tool get(String name) { return tools.get(name); }
    public Tool peek(String name) { return hiddenNames.contains(name) ? null : tools.get(name); }
    public void register(Tool tool) { tools.put(tool.getName(), tool); registrations++; }
    public void registerSystem(Tool tool) { register(tool); systemToolNames.add(tool.getName()); }
    public void unregister(String name) { tools.remove(name); }
    public void evict() { tools.clear(); }
    public int size() { return tools.size(); }
}
