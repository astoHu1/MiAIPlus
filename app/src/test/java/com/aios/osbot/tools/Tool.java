package com.aios.osbot.tools;

import java.util.List;

/** Contract-only fixture for the two pinned host versions; it contains no host implementation. */
public interface Tool {
    String getName();
    String getDisplayName();
    String getDescription();
    String getSummary();
    String getSearchHint();
    Object getParameters();
    String getCategory();
    String getRejects();
    List<?> getScope();
    List<?> getTags();
    int getDisplayDescriptionResId();
    long getTimeoutMs();
    long resolveTimeoutMs();
    boolean isStoppable();
    Object onCancel();
    Object execute(Object arguments, Object executionContext, Object continuation);
}
