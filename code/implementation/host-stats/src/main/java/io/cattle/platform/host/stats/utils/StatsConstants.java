package io.cattle.platform.host.stats.utils;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;


public class StatsConstants {

    public static final String LINK_STATS = "stats";
    public static final String CONTAINER_STATS = "containerStats";
    public static final String HOST_STATS = "hostStats";

    public static final ConfigProperty<String> CONTAINER_STATS_PATH = ArchaiusUtil.getStringProperty("link.containerstats.path");
    public static final ConfigProperty<String> HOST_STATS_PATH = ArchaiusUtil.getStringProperty("link.hoststats.path");

}