package com.floto.stt.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class FlotoAccessibilityService extends AccessibilityService {
    private static FlotoAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static boolean insertText(Context context, String rawText, boolean appendSpace) {
        String text = normalize(rawText, appendSpace);
        if (text.isEmpty()) {
            return true;
        }
        FlotoAccessibilityService service = instance;
        if (service == null) {
            copyToClipboard(context, text);
            return false;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            copyToClipboard(context, text);
            return false;
        }

        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) {
            target = findEditable(root);
        }
        if (target == null) {
            copyToClipboard(context, text);
            return false;
        }

        if (insertBySetText(target, text)) {
            return true;
        }
        copyToClipboard(context, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private static boolean insertBySetText(AccessibilityNodeInfo target, String text) {
        String current = getCurrentEditableText(target);
        int start = target.getTextSelectionStart();
        int end = target.getTextSelectionEnd();
        if (start < 0 || end < 0 || start > current.length() || end > current.length()) {
            start = current.length();
            end = current.length();
        }
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        String updated = current.substring(0, from) + text + current.substring(to);

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
        boolean set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (set) {
            Bundle selection = new Bundle();
            int cursor = from + text.length();
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
        }
        return set;
    }

    private static String getCurrentEditableText(AccessibilityNodeInfo target) {
        CharSequence currentChars = target.getText();
        if (currentChars == null) {
            return "";
        }
        String current = currentChars.toString();
        if (current.isEmpty()) {
            return "";
        }

        CharSequence hintChars = target.getHintText();
        boolean currentIsHint = hintChars != null && current.contentEquals(hintChars);
        if (target.isShowingHintText() || currentIsHint) {
            return "";
        }
        return current;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isEditable() && node.isFocused()) {
            return node;
        }
        if (node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findEditable(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static String normalize(String value, boolean appendSpace) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (appendSpace && !text.endsWith(" ")) {
            return text + " ";
        }
        return text;
    }

    private static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Floto transcript", text));
        }
    }
}
