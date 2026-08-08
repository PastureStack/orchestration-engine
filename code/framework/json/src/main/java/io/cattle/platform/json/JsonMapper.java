package io.cattle.platform.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface JsonMapper {

    Map<String, Object> readValue(InputStream is) throws IOException;

    Map<String, Object> readValue(byte[] bytes) throws IOException;

    Map<String, Object> readValue(String text) throws IOException;

    <T> T readValue(InputStream is, Class<T> type) throws IOException;

    <T> T readValue(byte[] bytes, Class<T> type) throws IOException;

    <T> T readValue(String text, Class<T> type) throws IOException;

    String writeValueAsString(Object object) throws IOException;

    byte[] writeValueAsBytes(Object data) throws IOException;

    Map<String, Object> writeValueAsMap(Object data);

    void writeValue(OutputStream baos, Object object) throws IOException;

    <E, C extends Collection<E>> C readCollectionValue(InputStream is, Class<C> collectionClass, Class<E> elementsClass) throws IOException;

    <E, C extends Collection<E>> C readCollectionValue(String content, Class<C> collectionClass, Class<E> elementsClass) throws IOException;

    <E> List<E> readListValue(InputStream is, Class<E> elementsClass) throws IOException;

    <E> List<E> readListValue(String content, Class<E> elementsClass) throws IOException;

    <T> T convertValue(Object fromValue, Class<T> toValueType);

    <E, C extends Collection<E>> C convertCollectionValue(Object fromValue, Class<C> collectionClass, Class<E> elementsClass);

    <E> List<E> convertListValue(Object fromValue, Class<E> elementsClass);
}
