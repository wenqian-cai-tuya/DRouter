package com.didi.drouter.generator;

import com.didi.drouter.plugin.RouterSetting;
import com.didi.drouter.utils.StoreUtil;
import com.didi.drouter.utils.TextUtil;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Loader;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;

/**
 * Created by gaowei on 2018/8/30
 */
abstract class AbsRouterCollect {

    static final String MATCH = "com.didi.drouter.match.";
    static final String PROXY = "com.didi.drouter.proxy.";
    static final String METHOD1 =
            "public java.lang.Object newInstance(android.content.Context context) {" +
            "   return null;" +
            "}";
    static final String METHOD2 =
            "public java.lang.Object callMethod(Object instance, String methodName, Object[] args) {" +
            "   return null;" +
            "}";

    abstract boolean collect(CtClass ct);
    abstract void generate(File routerDir) throws Exception;
    abstract boolean include(CtClass superCt);

    ClassPool pool;
    RouterSetting.Parse setting;
    Loader classLoader;

    AbsRouterCollect(ClassPool pool, RouterSetting.Parse setting) {
        this.pool = pool;
        this.setting = setting;
        this.classLoader = new Loader(pool);
    }

    String getPackageName() {
        if (TextUtil.isEmpty(setting.getPluginName())) {
            return "com.didi.drouter.loader.host";
        } else {
            return "com.didi.drouter.loader." + setting.getPluginName();
        }
    }

    Annotation getAnnotation(final CtClass ctClass, Class<?> annotation) {
        if (ctClass.isFrozen()) ctClass.defrost();

        ClassFile cf = ctClass.getClassFile();
        final AnnotationsAttribute visibleAttr =
                (AnnotationsAttribute) cf.getAttribute(AnnotationsAttribute.visibleTag);
        if (null != visibleAttr) {
            final Annotation sp = visibleAttr.getAnnotation(annotation.getName());
            if (null != sp) {
                return sp;
            }
        }

        final AnnotationsAttribute invisibleAttr =
                (AnnotationsAttribute) cf.getAttribute(AnnotationsAttribute.invisibleTag);
        if (null != invisibleAttr) {
            final Annotation sp = invisibleAttr.getAnnotation(annotation.getName());
            if (null != sp) {
                return sp;
            }
        }
        return null;
    }

    void generatorClass(File routerDir, CtClass ctClass, String... methods) throws Exception {
        for (String method : methods) {
            CtMethod ctMethod = CtNewMethod.make(method, ctClass);

            MethodInfo methodInfo = ctMethod.getMethodInfo();
            ConstPool constPool = ctClass.getClassFile().getConstPool();
            AnnotationsAttribute methodAttr = new AnnotationsAttribute(constPool, AnnotationsAttribute.visibleTag);
            Annotation overRide = new Annotation("java.lang.Override", constPool);
            methodAttr.addAnnotation(overRide);
            methodInfo.addAttribute(methodAttr);

            ctClass.addMethod(ctMethod);
        }
        ctClass.writeFile(routerDir.getCanonicalPath());
    }

    boolean isNonStaticInnerClass(CtClass ctClass) throws ClassNotFoundException {
        boolean possible = ctClass.getName().contains("$");
        if (possible) {
            Class<?> clz = StoreUtil.getClass(ctClass, classLoader);
            return (clz.getModifiers() & Modifier.STATIC) == 0;
        }
        return false;
    }

    // support static inner class, used for annotation "..A.B" not "..A$B"
    CtClass getCtClass(String className) throws NotFoundException {
        CtClass ctClass;
        NotFoundException exception = null;
        while (true) {
            try {
                ctClass = pool.get(className);
                break;
            } catch (NotFoundException e) {
                if (exception == null) exception = e;
                int index = className.lastIndexOf(".");
                if (index != -1) {
                    className = className.substring(0, index) + "$" + className.substring(index + 1);
                } else {
                    throw exception;
                }
            }
        }
        return ctClass;
    }

    // include self
    Set<CtClass> collectSuper(CtClass ct) {
        Set<CtClass> collect = new HashSet<>();
        try {
            while (ct != null) {
                collect.add(ct);
                collectInterface(ct, collect);
                ct = ct.getSuperclass();
            }
        } catch (NotFoundException e) {
            // ignore
        }
        return collect;
    }

    void collectInterface(CtClass ct, Set<CtClass> collect) {
        try {
            if (ct != null) {
                for (CtClass superInterface : ct.getInterfaces()) {
                    collect.add(superInterface);
                    collectInterface(superInterface, collect);
                }
            }
        } catch (NotFoundException e) {
            // ignore
        }
    }

    // check all super class and interface, include self
    // As long as any of the super ct contains classNames return yes.
    boolean checkSuper(CtClass ct, String... classNames) {
        return checkSuperInternal(ct, ct, classNames);
    }

    // check with error handling - skip corrupted classes instead of failing
    private boolean checkSuperInternal(CtClass ct, CtClass originalCt, String[] classNames) {
        try {
            while (ct != null) {
                if (match(ct, classNames)) {   //self
                    return true;
                }
                if (checkInterfaceInternal(ct, originalCt, classNames)) {
                    return true;
                }
                ct = ct.getSuperclass();
            }
        } catch (NotFoundException e) {
            // ignore
        } catch (RuntimeException e) {
            if (isZipError(e)) {
                handleJarReadError(ct, originalCt, e);
                // Skip this class and return false instead of throwing
                System.err.println("  >>> Skipping class due to corrupted JAR, build will continue <<<");
                return false;
            }
            throw e;
        }
        return false;
    }

    // ct can be class or interface, include self, tree
    private boolean checkInterface(CtClass ct, String... classNames) {
        return checkInterfaceInternal(ct, ct, classNames);
    }

    private boolean checkInterfaceInternal(CtClass ct, CtClass originalCt, String[] classNames) {
        if (ct == null) {
            return false;
        }
        if (match(ct, classNames)) {
            return true;
        }
        try {
            for (CtClass superInterface : ct.getInterfaces()) {
                boolean r = checkInterfaceInternal(superInterface, originalCt, classNames);
                if (r) {
                    return true;
                }
            }
        } catch (NotFoundException e) {
            // ignore
        } catch (RuntimeException e) {
            if (isZipError(e)) {
                handleJarReadError(ct, originalCt, e);
                // Skip this class and return false instead of throwing
                System.err.println("  >>> Skipping class due to corrupted JAR, build will continue <<<");
                return false;
            }
            throw e;
        }
        return false;
    }

    private boolean match(CtClass ct, String... classNames) {
        for (String name : classNames) {
            if (name.equals(ct.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if error is related to ZIP/JAR corruption
     */
    protected boolean isZipError(Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("ZipException") || msg.contains("ZipFile") || msg.contains("LOC header"))) {
            return true;
        }
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null && (causeMsg.contains("ZipException") || causeMsg.contains("LOC header"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if the class's JAR file is corrupted by trying to read its superclass
     */
    protected boolean isClassJarCorrupted(CtClass ct) {
        try {
            // Try to access superclass - this will trigger JAR read
            CtClass superClass = ct.getSuperclass();
            while (superClass != null) {
                // Try to get interfaces - another operation that reads JAR
                superClass.getInterfaces();
                superClass = superClass.getSuperclass();
            }
            return false;
        } catch (NotFoundException e) {
            return false; // Not found is not corruption
        } catch (RuntimeException e) {
            return isZipError(e);
        }
    }

    private void handleJarReadError(CtClass ct, CtClass originalClass, RuntimeException e) {
        String jarPath = getClassJarPath(ct);
        String originalJarPath = originalClass != null ? getClassJarPath(originalClass) : null;
        System.err.println("=== Corrupted JAR detected ===");
        System.err.println("  Processing class: " + (originalClass != null ? originalClass.getName() : ct.getName()));
        System.err.println("  Failed when loading: " + ct.getName());
        if (jarPath != null) {
            System.err.println("  Corrupted JAR file: " + jarPath);
        }
        if (originalJarPath != null && !originalJarPath.equals(jarPath)) {
            System.err.println("  Original class JAR: " + originalJarPath);
        }
        System.err.println("  Error: " + e.getMessage());
    }

    protected String getClassJarPath(CtClass ct) {
        try {
            java.net.URL url = ct.getURL();
            if (url != null) {
                String urlStr = url.toString();
                // jar:file:/path/to/file.jar!/com/example/Class.class
                if (urlStr.startsWith("jar:file:")) {
                    int idx = urlStr.indexOf("!/");
                    if (idx > 0) {
                        return urlStr.substring(9, idx);
                    }
                }
                return urlStr;
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
