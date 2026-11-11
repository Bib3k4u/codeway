package com.codeway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    private Json() {}

    public static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Map<String, String> files(String json) {
        try { return json == null ? new LinkedHashMap<>() : M.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {}); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static List<Object> list(String json) {
        try { return json == null ? new ArrayList<>() : M.readValue(json, new TypeReference<ArrayList<Object>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }
    public static Object any(String json) {
        try { return json == null ? null : M.readValue(json, Object.class); } catch (Exception e) { return null; }
    }
    public static List<String> strings(String json) {
        try { return json == null ? List.of() : M.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }
}
