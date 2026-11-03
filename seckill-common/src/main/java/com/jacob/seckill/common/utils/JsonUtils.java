package com.jacob.seckill.common.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.SneakyThrows;

import java.util.List;

/**
 * @author: jacob
 * @url: www.quanxiaoha.com
 * @date: 2026/11/3 22:42
 * @description: JSON 工具类
 **/
public class JsonUtils {

    private static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        OBJECT_MAPPER.registerModules(new JavaTimeModule()); // 解决 LocalDateTime 的序列化问题
    }

    /**
     * 使用外部配置好的 ObjectMapper 替换默认实例
     *
     * @param objectMapper
     */
    public static void init(ObjectMapper objectMapper) {
        OBJECT_MAPPER = objectMapper;
    }

    /**
     *  将对象转换为 JSON 字符串
     * @param obj
     * @return
     */
    @SneakyThrows
    public static String toJsonString(Object obj) {
       return OBJECT_MAPPER.writeValueAsString(obj);
    }

    /**
     * 将 JSON 字符串转换为指定类型的集合
     *
     * @param json
     * @param clazz
     * @return
     * @param <T>
     */
    @SneakyThrows
    public static <T> List<T> parseArray(String json, Class<T> clazz) {
        return OBJECT_MAPPER.readValue(json,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
    }

    /**
     * 将 JSON 字符串转换为指定类型的对象
     *
     * @param json
     * @param clazz
     * @return
     * @param <T>
     */
    @SneakyThrows
    public static <T> T parseObject(String json, Class<T> clazz) {
        return OBJECT_MAPPER.readValue(json, clazz);
    }

}
