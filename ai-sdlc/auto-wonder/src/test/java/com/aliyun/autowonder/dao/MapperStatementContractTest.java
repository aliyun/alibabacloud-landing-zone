package com.aliyun.autowonder.dao;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperStatementContractTest {

    private static final List<Class<? extends Annotation>> SQL_ANNOTATIONS = List.of(
            Select.class, Insert.class, Update.class, Delete.class,
            SelectProvider.class, InsertProvider.class, UpdateProvider.class, DeleteProvider.class,
            Flush.class);

    @Test
    void everyXmlMappedMapperMethodHasRegisteredStatement() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();

        List<Class<?>> mappers = new ArrayList<>(configuration.getMapperRegistry().getMappers());
        assertFalse(mappers.isEmpty(), "no mapper XML was parsed; check classpath*:mapping/*Dao.xml");

        List<String> missing = new ArrayList<>();
        for (Class<?> mapperInterface : mappers) {
            String namespace = mapperInterface.getName();
            for (Method method : mapperInterface.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic() || method.isBridge()
                        || Modifier.isStatic(method.getModifiers())
                        || hasSqlAnnotation(method)) {
                    continue;
                }
                String statementId = namespace + "." + method.getName();
                if (!configuration.hasStatement(statementId)) {
                    missing.add("namespace=" + namespace + ", method=" + method.getName()
                            + ", missing statement id=" + statementId);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "MyBatis statements missing from mapper XML:\n" + String.join("\n", missing));
    }

    private static boolean hasSqlAnnotation(Method method) {
        for (Class<? extends Annotation> annotation : SQL_ANNOTATIONS) {
            if (method.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }
}
