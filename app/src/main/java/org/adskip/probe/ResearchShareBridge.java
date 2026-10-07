package org.adskip.probe;

import android.content.Context;

import org.adskip.core.AnalysisRequestResolver;
import org.adskip.core.PageCatalog;
import org.adskip.core.ShareLinkObservation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflection boundary keeps all networking code absent from production and harness APKs. */
public final class ResearchShareBridge {
    public interface AnalysisCallback {
        void onComplete(String sanitizedSummary, ShareLinkObservation observation,
                PageCatalog catalog, AnalysisRequestResolver.Result resolution);
    }

    private ResearchShareBridge() {
    }

    public static boolean startAnalysis(Context context, String sharedText,
            AnalysisCallback callback) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return false;
        try {
            Class<?> type = Class.forName("org.adskip.probe.research.ResearchNetworkClient");
            Method method = type.getMethod(
                    "startAnalysis", Context.class, String.class, AnalysisCallback.class);
            return Boolean.TRUE.equals(method.invoke(null, context, sharedText, callback));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException exception) {
            callback.onComplete("request flow 启动失败："
                    + exception.getClass().getSimpleName(), null, null, null);
            return true;
        }
    }

    public static boolean startWebViewMatrix(Context context) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return false;
        try {
            Class<?> type = Class.forName(
                    "org.adskip.probe.research.ResearchWebViewMatrixActivity");
            Method method = type.getMethod("launch", Context.class);
            method.invoke(null, context);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException exception) {
            return false;
        }
    }
}
