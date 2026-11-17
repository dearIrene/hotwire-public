package dev.moon.hotwire.report;

import java.util.List;

public interface ReportDetails {
    List<String> describeMachine();

    String getMixinVersion();

    String getPlayerName();
}
