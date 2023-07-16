package org.squeryl.internals;

import java.lang.reflect.Method;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperMethod;
import net.bytebuddy.implementation.bind.annotation.This;

// XXX The ByteBuddy method interceptor needs to be a static method. There isn't a good way to guarantee this
// in Scala so just use Java to be sure.
public class PosoPropertyAccessInterceptorShim {
    @RuntimeType
    public static Object intercept(
            @This Object o,
            @Origin Method m,
            @AllArguments Object[] args,
            @SuperMethod Method superMethod) throws Throwable {
        return FieldReferenceLinker$PosoPropertyAccessInterceptor$.MODULE$.intercept(o, m, args, superMethod);
    }
}
