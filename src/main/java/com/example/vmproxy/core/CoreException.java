package com.example.vmproxy.core;

public class CoreException extends RuntimeException {
}

class NoClassException extends CoreException {}
class NoMethodException extends CoreException{}
