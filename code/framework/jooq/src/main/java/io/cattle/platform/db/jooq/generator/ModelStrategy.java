package io.cattle.platform.db.jooq.generator;

import io.cattle.platform.db.jooq.utils.TableRecordJaxb;

import java.util.List;

import org.jooq.codegen.DefaultGeneratorStrategy;
import org.jooq.codegen.GeneratorStrategy.Mode;
import org.jooq.meta.Definition;

public class ModelStrategy extends DefaultGeneratorStrategy {

    @Override
    public String getJavaSetterName(Definition definition, Mode mode) {
        String result = super.getJavaSetterName(definition, mode);
        if (isResourcePoolQualifier(definition)) {
            return "setResourcePoolQualifier";
        }
        switch (result) {
        case "setEnvironmentId":
            return "setStackId";
        case "setFolder":
            return "setGroup";
        }
        return result;
    }

    @Override
    public String getJavaGetterName(Definition definition, Mode mode) {
        String result = super.getJavaGetterName(definition, mode);
        if (isResourcePoolQualifier(definition)) {
            return "getResourcePoolQualifier";
        }
        switch (result) {
        case "getEnvironmentId":
            return "getStackId";
        case "getFolder":
            return "getGroup";
        }
        return result;
    }

    @Override
    public String getJavaIdentifier(Definition definition) {
        String result = super.getJavaIdentifier(definition);
        switch (result) {
        case "ENVIRONMENT":
            return "STACK";
        case "ENVIRONMENT_ID":
            return "STACK_ID";
        case "FOLDER":
            return "GROUP";
        }
        return result;
    }

    @Override
    public String getJavaClassName(Definition definition, Mode mode) {
        String result = getJavaClassName0(definition, mode);
        switch (result) {
        case "Environment":
            return "Stack";
        case "EnvironmentRecord":
            return "StackRecord";
        case "EnvironmentTable":
            return "StackTable";
        }
        return result;
    }

    protected String getJavaClassName0(Definition definition, Mode mode) {
        if (mode == Mode.INTERFACE) {
            String result = super.getJavaClassName(definition, mode);
            return result.startsWith("I") ? result.substring(1) : result;
        } else if (mode == Mode.DEFAULT) {
            return super.getJavaClassName(definition, mode) + "Table";
        }
        return super.getJavaClassName(definition, mode);
    }

    @Override
    public String getJavaPackageName(Definition definition, Mode mode) {
        if (mode == Mode.INTERFACE) {
            String result = super.getJavaPackageName(definition, mode);
            return result.replace(".tables.interfaces", "");
        }
        return super.getJavaPackageName(definition, mode);
    }

    @Override
    public List<String> getJavaClassImplements(Definition definition, Mode mode) {
        List<String> result = super.getJavaClassImplements(definition, mode);
        if (mode == Mode.RECORD) {
            result.add(TableRecordJaxb.class.getName());
        }
        return result;
    }

    protected boolean isResourcePoolQualifier(Definition definition) {
        return "qualifier".equals(definition.getName())
                && definition.getQualifiedName() != null
                && definition.getQualifiedName().endsWith(".resource_pool.qualifier");
    }

}
