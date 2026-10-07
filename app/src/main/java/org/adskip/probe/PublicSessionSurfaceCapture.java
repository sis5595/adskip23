package org.adskip.probe;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Explicit, in-memory diagnostic capture of public {@link MediaController} surfaces.
 *
 * <p>This deliberately does not inspect notifications, invoke a PendingIntent, or issue a media
 * command. It is a CID-evidence probe: values are shown only for scalar public fields after a
 * user presses the P5A button. URIs and unknown parcelables are redacted, and every collection is
 * bounded so that a producer cannot cause unbounded UI work.</p>
 */
public final class PublicSessionSurfaceCapture {
    private static final int MAX_TEXT_LENGTH = 160;
    private static final int MAX_BUNDLE_ENTRIES = 24;
    private static final int MAX_NESTED_BUNDLE_ENTRIES = 8;
    private static final int MAX_QUEUE_ITEMS = 16;
    private static final int MAX_BUNDLE_DEPTH = 1;

    private PublicSessionSurfaceCapture() {
    }

    /** Captures only the configured target package and returns a display-only report. */
    public static String capture(List<MediaController> controllers) {
        StringBuilder output = new StringBuilder();
        output.append("P5A 公开会话表面采集（本次手动触发；仅内存显示）\n");
        output.append("不读取通知正文；不联网；不保存；不调用播放、跳转或 PendingIntent。\n");

        int captured = 0;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                try {
                    if (controller == null
                            || !SessionProbe.BILIBILI_PACKAGE.equals(controller.getPackageName())) {
                        continue;
                    }
                    captured++;
                    output.append("\n--- target session #").append(captured).append(" ---\n");
                    appendController(output, controller);
                } catch (RuntimeException | LinkageError exception) {
                    captured++;
                    output.append("\n--- target session #").append(captured).append(" ---\n")
                            .append("session surface unavailable: ")
                            .append(exception.getClass().getSimpleName()).append('\n');
                }
            }
        }
        if (captured == 0) {
            output.append("未找到目标 MediaSession；先让目标客户端开始播放，再重新手动采集。\n");
        }
        output.append("采集字段只可作为候选 CID 证据；任何字段都不会直接启用跳过。\n");
        return output.toString();
    }

    private static void appendController(StringBuilder output, MediaController controller) {
        output.append("package: ").append(controller.getPackageName()).append('\n');
        MediaSession.Token token = controller.getSessionToken();
        output.append("session fingerprint: ")
                .append(token == null ? "<null>" : Integer.toHexString(token.hashCode()))
                .append('\n');

        appendBundle(output, "controller extras", controller.getExtras(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendBundle(output, "session info", controller.getSessionInfo(), 0);
        } else {
            output.append("session info: <API < 29>\n");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            output.append("session tag: ").append(limitText(controller.getTag())).append('\n');
        } else {
            output.append("session tag: <API < 30>\n");
        }
        SessionActivitySnapshot sessionActivity;
        try {
            sessionActivity = SessionActivitySnapshot.capture(
                    controller.getSessionActivity(), SessionProbe.BILIBILI_PACKAGE);
        } catch (RuntimeException | LinkageError exception) {
            sessionActivity = SessionActivitySnapshot.unreadable(
                    exception.getClass().getSimpleName());
        }
        output.append("session activity: state=").append(sessionActivity.getState())
                .append("; fingerprint=")
                .append(sessionActivity.getFingerprint(), 0, 12)
                .append("; immutable=")
                .append(sessionActivity.getImmutable() == null
                        ? "unknown" : sessionActivity.getImmutable())
                .append(" (not invoked; inner Intent unavailable)\n");

        appendMetadata(output, controller.getMetadata());
        appendPlaybackState(output, controller.getPlaybackState());
        appendQueue(output, controller.getQueue());
    }

    private static void appendMetadata(StringBuilder output, MediaMetadata metadata) {
        if (metadata == null) {
            output.append("metadata: <null>\n");
            return;
        }
        List<String> keys = sorted(metadata.keySet());
        output.append("metadata keys (").append(keys.size()).append("): ").append(keys).append('\n');
        appendMetadataValues(output, metadata, keys);
        MediaDescription description = metadata.getDescription();
        appendDescription(output, "metadata description", description);
    }

    /**
     * MediaMetadata exposes typed getters rather than a generic object getter. Enumerate only
     * text values and documented numeric keys, never render bitmaps, ratings, or custom objects.
     */
    private static void appendMetadataValues(
            StringBuilder output, MediaMetadata metadata, List<String> keys) {
        for (String key : keys) {
            try {
                String text = metadata.getString(key);
                if (text != null) {
                    output.append("metadata ").append(limitText(key)).append(" [String] = ")
                            .append(isUriLikeKey(key) ? redactUriWithOptionalCid(text) : limitText(text))
                            .append('\n');
                } else if (isDocumentedLongKey(key)) {
                    output.append("metadata ").append(limitText(key)).append(" [long] = ")
                            .append(metadata.getLong(key)).append('\n');
                } else {
                    output.append("metadata ").append(limitText(key))
                            .append(" [non-text/redacted]\n");
                }
            } catch (RuntimeException | LinkageError exception) {
                output.append("metadata ").append(limitText(key)).append(" = <unreadable ")
                        .append(exception.getClass().getSimpleName()).append(">\n");
            }
        }
    }

    private static void appendPlaybackState(StringBuilder output, PlaybackState state) {
        if (state == null) {
            output.append("playback state: <null>\n");
            return;
        }
        output.append("playback active queue item id: ")
                .append(state.getActiveQueueItemId()).append('\n');
        List<PlaybackState.CustomAction> actions = state.getCustomActions();
        if (actions == null || actions.isEmpty()) {
            output.append("playback custom actions: <none>\n");
        } else {
            output.append("playback custom actions (").append(actions.size()).append("): ");
            int limit = Math.min(actions.size(), MAX_QUEUE_ITEMS);
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    output.append(", ");
                }
                PlaybackState.CustomAction action = actions.get(index);
                output.append(action == null ? "<null>" : limitText(action.getAction()));
            }
            if (actions.size() > limit) {
                output.append(" … +").append(actions.size() - limit);
            }
            output.append('\n');
        }
        appendBundle(output, "playback extras", state.getExtras(), 0);
    }

    private static void appendQueue(StringBuilder output, List<MediaSession.QueueItem> queue) {
        if (queue == null) {
            output.append("queue: <null>\n");
            return;
        }
        output.append("queue item count: ").append(queue.size()).append('\n');
        int limit = Math.min(queue.size(), MAX_QUEUE_ITEMS);
        for (int index = 0; index < limit; index++) {
            MediaSession.QueueItem item = queue.get(index);
            if (item == null) {
                output.append("queue[").append(index).append("]: <null>\n");
                continue;
            }
            output.append("queue[").append(index).append("] id=")
                    .append(item.getQueueId()).append('\n');
            appendDescription(output, "queue[" + index + "] description", item.getDescription());
        }
        if (queue.size() > limit) {
            output.append("queue: ").append(queue.size() - limit)
                    .append(" items omitted by diagnostic limit\n");
        }
    }

    private static void appendDescription(
            StringBuilder output, String label, MediaDescription description) {
        if (description == null) {
            output.append(label).append(": <null>\n");
            return;
        }
        output.append(label).append(" media id: ")
                .append(limitText(description.getMediaId())).append('\n');
        output.append(label).append(" title: ")
                .append(limitText(description.getTitle())).append('\n');
        output.append(label).append(" subtitle: ")
                .append(limitText(description.getSubtitle())).append('\n');
        output.append(label).append(" text: ")
                .append(limitText(description.getDescription())).append('\n');
        output.append(label).append(" media uri present: ")
                .append(description.getMediaUri() != null).append(" (redacted)\n");
        appendBundle(output, label + " extras", description.getExtras(), 0);
    }

    private static void appendBundle(StringBuilder output, String label, Bundle bundle, int depth) {
        if (bundle == null) {
            output.append(label).append(": <null>\n");
            return;
        }
        final List<String> keys;
        try {
            keys = sorted(bundle.keySet());
        } catch (RuntimeException | LinkageError exception) {
            output.append(label).append(": <unreadable ")
                    .append(exception.getClass().getSimpleName()).append(">\n");
            return;
        }
        if (keys.isEmpty()) {
            output.append(label).append(": {}\n");
            return;
        }
        output.append(label).append(" keys (").append(keys.size()).append("):\n");
        int entryLimit = depth == 0 ? MAX_BUNDLE_ENTRIES : MAX_NESTED_BUNDLE_ENTRIES;
        int limit = Math.min(keys.size(), entryLimit);
        for (int index = 0; index < limit; index++) {
            String key = keys.get(index);
            try {
                Object value = bundle.get(key);
                output.append(indent(depth + 1)).append(limitText(key)).append(" [")
                        .append(typeOf(value)).append("] = ")
                        .append(renderValue(value)).append('\n');
                if (value instanceof Bundle && depth < MAX_BUNDLE_DEPTH) {
                    appendBundle(output, indent(depth + 2) + limitText(key), (Bundle) value, depth + 1);
                }
            } catch (RuntimeException | LinkageError exception) {
                output.append(indent(depth + 1)).append(limitText(key)).append(" = <unreadable ")
                        .append(exception.getClass().getSimpleName()).append(">\n");
            }
        }
        if (keys.size() > limit) {
            output.append(indent(depth + 1)).append("… ")
                    .append(keys.size() - limit).append(" keys omitted by diagnostic limit\n");
        }
    }

    private static String renderValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return limitText(String.valueOf(value));
        }
        if (value instanceof Bundle) {
            return "<nested Bundle>";
        }
        if (value.getClass().isArray()) {
            return "<array length=" + Array.getLength(value) + ">";
        }
        if (value instanceof List || value instanceof Set) {
            return "<collection size=" + ((java.util.Collection<?>) value).size() + ">";
        }
        if (value instanceof android.net.Uri) {
            return "<URI redacted>";
        }
        return "<redacted>";
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static boolean isDocumentedLongKey(String key) {
        return MediaMetadata.METADATA_KEY_DURATION.equals(key)
                || MediaMetadata.METADATA_KEY_YEAR.equals(key)
                || MediaMetadata.METADATA_KEY_TRACK_NUMBER.equals(key)
                || MediaMetadata.METADATA_KEY_NUM_TRACKS.equals(key)
                || MediaMetadata.METADATA_KEY_DISC_NUMBER.equals(key)
                || MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE.equals(key);
    }

    private static boolean isUriLikeKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("uri") || normalized.contains("url");
    }

    /**
     * Keeps URI contents private. A positive decimal {@code cid} query parameter is the only
     * allowlisted fragment that may be exposed, and it remains only a diagnostic candidate.
     */
    private static String redactUriWithOptionalCid(String value) {
        try {
            Uri uri = Uri.parse(value);
            String candidate = uri.getQueryParameter("cid");
            if (candidate != null && candidate.matches("[1-9][0-9]{0,18}")) {
                return "<URI redacted; cid-candidate=" + candidate + ">";
            }
        } catch (RuntimeException exception) {
            // A malformed URI stays redacted; the raw value must never become a fallback.
        }
        return "<URI redacted>";
    }

    private static List<String> sorted(Set<String> values) {
        List<String> output = new ArrayList<>(values);
        Collections.sort(output);
        return output;
    }

    private static String limitText(CharSequence value) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.toString().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        if (normalized.length() <= MAX_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_TEXT_LENGTH) + "…";
    }

    private static String indent(int depth) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            output.append("  ");
        }
        return output.toString();
    }
}
