package org.testar.asm.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.Objects;
import java.util.Set;

/** A selectable click, configured fill, or browser goBack; values stay out of descriptions. */
public final class BrowserAction {
    private final String actionType;
    private final String name;
    private final Locator locator;
    private final String value;
    private final String targetSignature;
    private final String targetPath;
    private final Page page;

    public BrowserAction(String actionType, String name, Locator locator) {
        this(actionType, name, locator, null);
    }

    public BrowserAction(String actionType, String name, Locator locator, String value) {
        this(actionType, name, locator, value, null, null, null);
    }

    BrowserAction(String actionType, String name, Locator locator, String value,
                  String targetSignature, String targetPath, Page page) {
        if (!Set.of("click", "fill", "go_back").contains(Objects.requireNonNull(actionType))) {
            throw new IllegalArgumentException("Supported action types are click, fill, and go_back");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Action name must be nonblank");
        }
        if (actionType.equals("go_back")) {
            if (page == null || locator != null) {
                throw new IllegalArgumentException("Go back requires a page and no widget locator");
            }
        } else {
            Objects.requireNonNull(locator, "locator");
        }
        if (actionType.equals("fill") && value == null) {
            throw new IllegalArgumentException("A fill action requires a string value");
        }
        this.actionType = actionType;
        this.name = name;
        this.locator = locator;
        this.value = value;
        this.targetSignature = targetSignature;
        this.targetPath = targetPath;
        this.page = page;
    }

    public static BrowserAction goBack(Page page) {
        return new BrowserAction("go_back", "Go back in browser history", null, null, null, null, page);
    }

    public String actionType() {
        return actionType;
    }

    public String name() {
        return name;
    }

    public Locator locator() {
        return locator;
    }

    public String value() {
        return value;
    }

    Page ownerPage() {
        return locator == null ? page : locator.page();
    }

    String targetPath() {
        return targetPath;
    }

    /** Recheck the actual control; a changed DOM path must not silently select a different widget. */
    public boolean applicable() {
        if (actionType.equals("go_back")) {
            return !page.isClosed() && Boolean.TRUE.equals(page.evaluate("""
                () => window.navigation && typeof window.navigation.canGoBack === "boolean"
                    ? window.navigation.canGoBack : window.history.length > 1
                """));
        }
        if (locator.count() != 1 || !locator.isVisible() || !locator.isEnabled()
                || actionType.equals("fill") && !locator.isEditable()) {
            return false;
        }
        return targetSignature == null || targetSignature.equals(locator.evaluate("""
            element => JSON.stringify([element.localName, element.id, element.getAttribute("name"),
                element.getAttribute("type"), element.getAttribute("href"),
                element.getAttribute("aria-label"), element.textContent])
            """));
    }

    /** Invoke the official Playwright operation; the driver records its outcome. */
    public void execute(double timeout) {
        switch (actionType) {
            case "fill" -> locator.fill(value, new Locator.FillOptions().setTimeout(timeout));
            case "go_back" -> page.goBack(new Page.GoBackOptions().setTimeout(timeout).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            default -> locator.click(new Locator.ClickOptions().setTimeout(timeout));
        }
    }

    @Override
    public String toString() {
        return "BrowserAction[" + actionType + ", " + name + "]";
    }
}
