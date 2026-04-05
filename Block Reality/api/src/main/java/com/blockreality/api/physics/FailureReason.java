package com.blockreality.api.physics;

/**
 * 結構失效原因 — 包含失效類型與詳細描述。
 */
public record FailureReason(FailureType type, String detail) {}
