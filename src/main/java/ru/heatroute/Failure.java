package ru.heatroute;

import java.util.*;

public final class Failure extends RuntimeException {
  public final String code, featureId;

  public Failure(String code, String message) {
    this(code, message, null);
  }

  public Failure(String code, String message, String id) {
    super(message);
    this.code = code;
    this.featureId = id;
  }

  public Map<String, Object> json() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("code", code);
    d.put("message", getMessage());
    if (featureId != null) d.put("feature_id", featureId);
    return Map.of("error", d);
  }
}
