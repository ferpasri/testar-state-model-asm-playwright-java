package org.testar.asm.playwright.benchmark;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Benchmark-only wrappers preserve native calls, results, and exceptions. */
final class BenchmarkBrowser {
    private final BenchmarkTimings timings;
    private final Map<Object, Object> wrappers = new IdentityHashMap<>();
    final List<Map<String, String>> actions = new ArrayList<>();

    BenchmarkBrowser(BenchmarkTimings timings) {
        this.timings = timings;
    }

    Page page(Page nativePage) {
        return (Page) wrap(nativePage);
    }

    private Object wrap(Object value) {
        Class<?> type = value instanceof Page ? Page.class : value instanceof Locator ? Locator.class : null;
        if (type == null) {
            return value;
        }
        return wrappers.computeIfAbsent(value, original -> Proxy.newProxyInstance(type.getClassLoader(),
            new Class<?>[]{type}, (proxy, method, arguments) -> invoke(original, method, arguments)));
    }

    private Object invoke(Object original, Method method, Object[] arguments) throws Throwable {
        String category = category(original, method.getName(), arguments);
        if (original instanceof Locator && Set.of("click", "fill").contains(method.getName())) {
            actions.add(Map.of("type", method.getName(), "locator", original.toString()));
        }
        try {
            Object result;
            if (category == null) {
                result = method.invoke(original, arguments);
            } else {
                try (var ignored = timings.measure(category)) {
                    result = method.invoke(original, arguments);
                }
            }
            return wrap(result);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static String category(Object original, String method, Object[] arguments) {
        if (original instanceof Page) {
            if (method.equals("screenshot")) {
                return "screenshots";
            }
            if (method.equals("evaluate") && arguments != null && arguments.length > 1
                    && arguments[1] instanceof Map<?, ?> options && options.containsKey("maxWidgets")) {
                return "dom_capture";
            }
        }
        if (original instanceof Locator) {
            if (Set.of("click", "fill").contains(method)) {
                return "browser_actions";
            }
            if (Set.of("count", "isVisible", "isEnabled", "isEditable").contains(method)
                    || method.equals("evaluate") && arguments != null && arguments.length > 0
                    && arguments[0] instanceof String script && script.contains("JSON.stringify([")) {
                return "applicability";
            }
        }
        return null;
    }
}
