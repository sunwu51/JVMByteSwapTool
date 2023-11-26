package com.example.vmproxy.core;


import java.util.List;
import java.util.Objects;

public class MethodId {
    String className;

    String method;

    List<String> paramTypes;

    public MethodId(String className, String method, List<String> paramTypes) {
        this.className = className;
        this.method = method;
        this.paramTypes = paramTypes;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MethodId methodId = (MethodId) o;

        if (!Objects.equals(className, methodId.className)) return false;
        if (!Objects.equals(method, methodId.method)) return false;
        return Objects.equals(paramTypes, methodId.paramTypes);
    }

    @Override
    public int hashCode() {
        int result = className != null ? className.hashCode() : 0;
        result = 31 * result + (method != null ? method.hashCode() : 0);
        result = 31 * result + (paramTypes != null ? paramTypes.hashCode() : 0);
        return result;
    }

    public String getClassName() {
        return className;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public List<String> getParamTypes() {
        return paramTypes;
    }

    public void setParamTypes(List<String> paramTypes) {
        this.paramTypes = paramTypes;
    }
}
