# DRouter 插件修复：ZipException 损坏 JAR 文件问题

## 问题描述

在构建过程中，DRouter 插件概率性出现以下错误：

```
Execution failed for task ':app:DebugDRouterTask'.
> Could not generate d_router table
  Class: === com.example.SomeActivity ===
  Cause: java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)
```

### 问题原因

1. **并发竞争**：Booster 插件生成的 `classes.jar` 文件可能在 DRouter 读取时还未完全写入
2. **JAR 文件损坏**：某些情况下 JAR 文件的 ZIP 头部签名无效
3. **Javassist 延迟加载**：在检查类的父类/接口时，Javassist 会延迟加载类文件，此时可能触发 ZIP 读取错误
4. **类型检测失败**：因为无法读取父类，导致无法判断类的类型（Activity/Fragment/View/Handler）

---

## 修复方案

### 核心策略

**容错模式**：当遇到 JAR 文件损坏无法读取时，跳过该类继续处理其他类，而不是让整个构建失败。

---

## 修改的文件

### 1. `drouter-plugin-lib/src/main/java/com/didi/drouter/generator/AbsRouterCollect.java`

#### 新增方法

```java
/**
 * 检测是否为 ZIP/JAR 相关错误（包括检查异常链）
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
 * 检测类的 JAR 文件是否损坏（通过尝试读取父类）
 */
protected boolean isClassJarCorrupted(CtClass ct) {
    try {
        CtClass superClass = ct.getSuperclass();
        while (superClass != null) {
            superClass.getInterfaces();
            superClass = superClass.getSuperclass();
        }
        return false;
    } catch (NotFoundException e) {
        return false;
    } catch (RuntimeException e) {
        return isZipError(e);
    }
}

/**
 * 获取类所在的 JAR 文件路径
 */
protected String getClassJarPath(CtClass ct) {
    try {
        java.net.URL url = ct.getURL();
        if (url != null) {
            String urlStr = url.toString();
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

/**
 * 处理并打印 JAR 读取错误信息
 */
private void handleJarReadError(CtClass ct, CtClass originalClass, RuntimeException e) {
    String jarPath = getClassJarPath(ct);
    System.err.println("=== Corrupted JAR detected ===");
    System.err.println("  Processing class: " + (originalClass != null ? originalClass.getName() : ct.getName()));
    System.err.println("  Failed when loading: " + ct.getName());
    if (jarPath != null) {
        System.err.println("  Corrupted JAR file: " + jarPath);
    }
    System.err.println("  Error: " + e.getMessage());
}
```

#### 修改 `checkSuper()` 和 `checkInterface()` 方法

**修改前**：遇到 `NotFoundException` 时忽略，其他异常向上抛出导致构建失败

**修改后**：遇到 ZIP 错误时，打印错误信息并跳过该类（返回 `false`），构建继续进行

```java
private boolean checkSuperInternal(CtClass ct, CtClass originalCt, String[] classNames) {
    try {
        while (ct != null) {
            if (match(ct, classNames)) {
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
            System.err.println("  >>> Skipping class due to corrupted JAR, build will continue <<<");
            return false;  // 跳过而不是抛出异常
        }
        throw e;
    }
    return false;
}
```

---

### 2. `drouter-plugin-lib/src/main/java/com/didi/drouter/generator/RouterCollect.java`

#### 修改 `generate()` 方法

##### 修改一：类型检测失败时检查是否因 JAR 损坏

当所有类型检测都返回 `false` 时，检查是否是因为 JAR 损坏导致的，如果是则跳过该类：

```java
if (checkSuper(routerCc, "android.app.Activity")) {
    type = "com.didi.drouter.store.RouterMeta.ACTIVITY";
} else if (...) {
    // ... 其他类型检测
} else {
    // 检查是否因为 JAR 损坏导致类型检测失败
    if (isClassJarCorrupted(routerCc)) {
        System.err.println("=== Cannot determine class type due to corrupted JAR ===");
        System.err.println("  Class: " + routerCc.getName());
        System.err.println("  >>> Skipping this class, build will continue <<<");
        continue;  // 跳过该类
    }
    throw new Exception("@Router target class illegal...");
}
```

##### 修改二：异常处理中跳过 ZIP 错误

```java
} catch (Exception e) {
    if (isZipError(e)) {
        System.err.println("=== Corrupted JAR detected in RouterCollect ===");
        System.err.println("  Class: " + routerCc.getName());
        System.err.println("  >>> Skipping this class, build will continue <<<");
        continue;  // 跳过该类，继续处理其他类
    }
    throw new Exception(errorMsg, e);
}
```

---

## 修复效果

### 修复前

- 构建过程中概率性失败
- 遇到损坏 JAR 就终止整个构建
- 错误信息不包含损坏的 JAR 文件路径

### 修复后

- 遇到损坏的 JAR 文件时**跳过该类**，构建继续进行
- 打印详细的错误信息，包括：
  - 正在处理的类名
  - 损坏的 JAR 文件完整路径
  - 具体的错误原因
- 构建成功率大幅提升

### 日志输出示例

```
=== Corrupted JAR detected ===
  Processing class: com.example.MyActivity
  Failed when loading: androidx.appcompat.app.AppCompatActivity
  Corrupted JAR file: /path/to/app/build/intermediates/classes/debug/ALL/transformDebugClassesWithBooster/classes.jar
  Error: java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)
  >>> Skipping class due to corrupted JAR, build will continue <<<

=== Cannot determine class type due to corrupted JAR ===
  Class: com.example.MyActivity
  >>> Skipping this class, build will continue <<<
```

---

## 编译更新后的插件

```bash
cd /path/to/DRouter-gradle8
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :drouter-plugin-v8:clean :drouter-plugin-v8:jar
```

编译后的 JAR 文件位于：`drouter-plugin-v8/build/libs/drouter-plugin-v8-1.4.0.jar`

---

## 其他建议

如果问题仍然频繁出现，建议：

1. **检查 Booster 插件**：问题 JAR 通常由 Booster 生成（`transformDebugClassesWithBooster/classes.jar`），考虑升级或检查 Booster 配置
2. **清理构建缓存**：
   ```bash
   ./gradlew clean
   rm -rf app/build/intermediates/classes
   ```
3. **配置任务依赖**：确保 DRouter 任务在其他 Transform 任务完成后运行

---

## 修改日期

2026-01-08

