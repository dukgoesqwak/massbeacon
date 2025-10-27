package com.massbeacon;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Provides;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
        name = "MassBeacon",
        description = "Simple beacon poster/fetcher for mass activities.",
        enabledByDefault = true
)
public class MassBeaconPlugin extends Plugin
{
    private static final boolean AUTO_POST_ENABLED = true;
    private static final boolean NETWORK_BEACONS_ENABLED = true;
    private static final boolean INCLUDE_PLAYER_COUNT = true;
    private static final int AUTO_POST_INTERVAL_SEC = 15; // post cadence

    private static final URI BEACON_ENDPOINT = URI.create(
            "https://massbeacon-worker.dskill4.workers.dev/beacon"
    );

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new GsonBuilder().create();

    @Inject private Client client;
    @Inject private OkHttpClient httpClient;
    @Inject private ConfigManager configManager;
    @Inject private MassBeaconConfig config;
    @Inject private ScheduledExecutorService executor;
    @Inject private OverlayManager overlayManager;
    @Inject private MassBeaconOverlay overlay;

    private ScheduledFuture<?> fetchTask;
    private ScheduledFuture<?> autoPostTask;
    private volatile Instant lastPost = Instant.EPOCH;

    public static final class WorldCount { public int world; public int count; }
    private volatile List<WorldCount> latestWorlds = Collections.emptyList();
    public List<WorldCount> getLatestWorlds() { return latestWorlds; }

    private String lastDiscordSig = null;
    private Instant lastDiscordAt = Instant.EPOCH;

    // region tracking
    volatile int lastRegionId = -1;
    private int lastLoggedRegion = -1;

    // track whether we are currently inside the target area (for clearing overlay when we leave)
    private boolean wasInTargetArea = false;

    private static final String CFG_GROUP = "massbeacon";
    private static final String[] DEPRECATED_KEYS = {
            "autoPost", "beaconEndpoint", "copyHotkey",
            "customActivityEnabled", "customActivityName",
            "includePlayerCount", "enableNetworkBeacons",
            "corpRegionIds"
    };

    // ✅ Updated BA regions based on your logs
    //  - 10322: BA outpost / lobby area
    //  - 10039: outside BA (adjacent area you crossed)
    private static final int[] BA_REGIONS   = { 10322, 10039 };
    // Leave Corp as-is (can update when you share its regions)
    private static final int[] CORP_REGIONS = { 11842, 11844 };

    @Provides
    MassBeaconConfig provideConfig(ConfigManager cm) { return cm.getConfig(MassBeaconConfig.class); }

    @Override
    protected void startUp()
    {
        for (String k : DEPRECATED_KEYS) { configManager.unsetConfiguration(CFG_GROUP, k); }
        overlayManager.add(overlay);

        if (NETWORK_BEACONS_ENABLED) scheduleFetch();
        if (AUTO_POST_ENABLED)       scheduleAutoPost();

        log.info("MassBeacon started.");
    }

    @Override
    protected void shutDown()
    {
        if (fetchTask != null) { fetchTask.cancel(true); fetchTask = null; }
        if (autoPostTask != null) { autoPostTask.cancel(true); autoPostTask = null; }
        latestWorlds = Collections.emptyList();
        lastDiscordSig = null;
        lastDiscordAt = Instant.EPOCH;
        overlayManager.remove(overlay);
        log.info("MassBeacon stopped.");
    }

    // ---------- Region change logger + leave detection ----------
    @Subscribe
    public void onGameTick(GameTick e)
    {
        if (client.getLocalPlayer() == null) return;
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();
        if (wp == null) return;

        int r = wp.getRegionID();
        lastRegionId = r;

        if (r != lastLoggedRegion)
        {
            lastLoggedRegion = r;
            log.info("MassBeacon: region change -> region={} x={} y={} plane={} inBA={} inCorp={}",
                    r, wp.getX(), wp.getY(), wp.getPlane(), rawIsInBA(r), rawIsInCorp(r));
        }

        // Detect entering/exiting target area and clear overlay on exit
        boolean inAreaNow = resolveActivityOrNull() != null;
        if (inAreaNow != wasInTargetArea)
        {
            if (!inAreaNow && !latestWorlds.isEmpty())
            {
                latestWorlds = Collections.emptyList();
                log.info("MassBeacon: left target area (region={}), overlay cleared", lastRegionId);
            }
            wasInTargetArea = inAreaNow;
        }
    }

    // ---------- One-shot POST/FETCH on login ----------
    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGGED_IN)
        {
            executor.schedule(this::debugImmediatePostAndFetch, 1, TimeUnit.SECONDS);
        }
    }

    private void debugImmediatePostAndFetch()
    {
        if (!isReadyInGame()) return;

        final String activity = resolveActivityOrNull();
        if (activity == null)
        {
            log.info("MassBeacon: immediate tick outside target area; skipping post/fetch (region={})", lastRegionId);
            return;
        }

        final int world = client.getWorld();
        final int players = getPlayerCount();

        log.info("MassBeacon: IMMEDIATE POST -> '{}' W{} players={}", activity, world, players);
        doPost(activity, world, players, shouldNotifyDiscord(activity, world, players));

        doFetch(activity); // fetch the same activity immediately
    }

    // ---------- Scheduling ----------
    private void scheduleFetch()
    {
        int secs = clamp(config.fetchIntervalSec(), 5, 120);
        if (fetchTask != null) fetchTask.cancel(true);
        fetchTask = executor.scheduleWithFixedDelay(this::doFetchSafe, secs, secs, TimeUnit.SECONDS);
        log.debug("MassBeacon: scheduled fetch every {}s", secs);
    }

    private void scheduleAutoPost()
    {
        if (autoPostTask != null) autoPostTask.cancel(true);
        autoPostTask = executor.scheduleWithFixedDelay(
                this::maybeAutoPostSafe,
                AUTO_POST_INTERVAL_SEC,
                AUTO_POST_INTERVAL_SEC,
                TimeUnit.SECONDS
        );
        log.debug("MassBeacon: scheduled auto-post every {}s", AUTO_POST_INTERVAL_SEC);
    }

    private void doFetchSafe()
    {
        try
        {
            if (!isReadyInGame()) return;
            final String activity = resolveActivityOrNull();
            if (activity == null) return;
            doFetch(activity);
        }
        catch (Exception e) { log.debug("Fetch failed", e); }
    }

    private void maybeAutoPostSafe()
    {
        try
        {
            if (!isReadyInGame()) return;

            final String activity = resolveActivityOrNull();
            if (activity == null) return; // hard stop outside area

            final int world = client.getWorld();
            final int players = getPlayerCount();
            final boolean notifyDiscord = shouldNotifyDiscord(activity, world, players);

            log.debug("MassBeacon: POST tick -> '{}' W{} players={} notifyDiscord={}",
                    activity, world, players, notifyDiscord);

            doPost(activity, world, players, notifyDiscord);
            lastPost = Instant.now();

            if (notifyDiscord)
            {
                lastDiscordSig = discordSig(activity, world, players);
                lastDiscordAt = Instant.now();
            }
        }
        catch (Exception e) { log.debug("Auto-post failed", e); }
    }

    // ---------- Core network calls ----------
    private void doPost(String activity, int world, int playerCount, boolean notifyDiscord)
    {
        try
        {
            Map<String, Object> payload = buildBeaconPayload(activity, world, playerCount);
            String json = GSON.toJson(payload);

            Request req = new Request.Builder()
                    .url(BEACON_ENDPOINT.toString())
                    .post(RequestBody.create(JSON, json))
                    .build();

            log.info("MassBeacon: POST /beacon -> {}", BEACON_ENDPOINT);

            httpClient.newCall(req).enqueue(new Callback()
            {
                @Override public void onFailure(Call call, IOException e)
                {
                    log.warn("MassBeacon: POST failed: {}", e.toString());
                }

                @Override public void onResponse(Call call, Response response) throws IOException
                {
                    int code = response.code();
                    response.close();
                    log.info("MassBeacon: POST /beacon HTTP {}", code);
                    if (code < 200 || code >= 300)
                    {
                        log.warn("MassBeacon: POST non-2xx ({}), body likely rejected", code);
                    }

                    // Update overlay immediately with optimistic data
                    tryUpdateOverlayOptimistically(world, playerCount);

                    if (notifyDiscord)
                    {
                        postToDiscordAsync(activity, world, playerCount);
                    }

                    // Immediately fetch to refresh overlay data for the same activity
                    executor.execute(() -> {
                        try { doFetch(activity); } catch (Exception ignored) {}
                    });
                }
            });
        }
        catch (Exception e)
        {
            log.warn("MassBeacon: POST build/queue error: {}", e.toString());
        }
    }

    private void doFetch(String activity)
    {
        try
        {
            HttpUrl url = new HttpUrl.Builder()
                    .scheme("https")
                    .host("massbeacon-worker.dskill4.workers.dev")
                    .addPathSegment("summary")
                    .addQueryParameter("activity", activity)
                    .build();

            Request req = new Request.Builder().url(url).get().build();

            log.info("MassBeacon: GET {}", url);

            httpClient.newCall(req).enqueue(new Callback()
            {
                @Override public void onFailure(Call call, IOException e)
                {
                    log.warn("MassBeacon: GET failed: {}", e.toString());
                }

                @Override public void onResponse(Call call, Response response) throws IOException
                {
                    int code = response.code();
                    if (!response.isSuccessful())
                    {
                        log.warn("MassBeacon: GET non-2xx: HTTP {}", code);
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    class Summary { List<WorldCount> worlds; }
                    Summary s = GSON.fromJson(body, Summary.class);

                    // Do NOT overwrite overlay with empty results (KV may be briefly empty)
                    if (s == null || s.worlds == null)
                    {
                        log.info("MassBeacon: summary parse -> null (keeping previous)");
                        return;
                    }
                    if (s.worlds.isEmpty())
                    {
                        log.info("MassBeacon: summary empty (keeping previous)");
                        return;
                    }

                    latestWorlds = s.worlds;
                    log.info("MassBeacon: summary worlds {}", latestWorlds.size());
                }
            });
        }
        catch (Exception e)
        {
            log.warn("MassBeacon: GET build/queue error: {}", e.toString());
        }
    }

    private void postToDiscordAsync(String activity, int world, int playerCount)
    {
        String hook = config.webhookUrl();
        if (Strings.isNullOrEmpty(hook)) return;
        Map<String, Object> disc = new HashMap<>();
        disc.put("content", String.format("🔔 **%s** mass at **W%d** (%d players)", activity, world, playerCount));

        Request req = new Request.Builder()
                .url(hook)
                .post(RequestBody.create(JSON, GSON.toJson(disc)))
                .build();

        httpClient.newCall(req).enqueue(new Callback()
        {
            @Override public void onFailure(Call call, IOException e)
            { log.warn("MassBeacon: Discord failed: {}", e.toString()); }

            @Override public void onResponse(Call call, Response response) throws IOException
            {
                int code = response.code();
                response.close();
                log.info("MassBeacon: Discord HTTP {}", code);
            }
        });
    }

    // ---------- Helpers ----------
    private Map<String, Object> buildBeaconPayload(String activity, int world, int playerCount)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("activity", activity);
        m.put("world", world);
        if (INCLUDE_PLAYER_COUNT) m.put("players", playerCount);
        return m;
    }

    /**
     * Only operate when actually in the target area (per toggles + region list).
     * Returns null outside area.
     */
    private String resolveActivityOrNull()
    {
        final boolean wantBA   = config.onlyAtBA();
        final boolean wantCorp = config.onlyAtCorp();
        final boolean inBA     = isInBARegion();
        final boolean inCorp   = isInCorpRegion();

        if (wantBA && inBA)     return "Barbarian Assault";
        if (wantCorp && inCorp) return "Corporeal Beast";

        if (!wantBA && !wantCorp)
        {
            if (inBA)   return "Barbarian Assault";
            if (inCorp) return "Corporeal Beast";
        }
        return null;
    }

    private boolean isInBARegion()
    {
        int r = currentRegionId();
        if (r == -1) return false;
        for (int id : BA_REGIONS) if (r == id) return true;
        return false;
    }

    private boolean isInCorpRegion()
    {
        int r = currentRegionId();
        if (r == -1) return false;
        for (int id : CORP_REGIONS) if (r == id) return true;
        return false;
    }

    private boolean rawIsInBA(int regionId)
    {
        for (int id : BA_REGIONS) if (regionId == id) return true;
        return false;
    }

    private boolean rawIsInCorp(int regionId)
    {
        for (int id : CORP_REGIONS) if (regionId == id) return true;
        return false;
    }

    private int currentRegionId()
    {
        if (client.getLocalPlayer() == null) return -1;
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();
        if (wp == null) return -1;
        lastRegionId = wp.getRegionID();
        return lastRegionId;
    }

    private boolean isReadyInGame()
    {
        return client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
    }

    private int getPlayerCount()
    {
        try
        {
            if (client.getGameState() != GameState.LOGGED_IN) return 0;
            int c = 0; for (Player p : client.getPlayers()) { if (p != null) c++; }
            return c;
        }
        catch (Exception e) { return 0; }
    }

    private boolean shouldNotifyDiscord(String activity, int world, int players)
    {
        if (players <= 0) return false;
        if (client.getGameState() != GameState.LOGGED_IN) return false;

        String sig = discordSig(activity, world, players);
        if (sig.equals(lastDiscordSig) && Instant.now().isBefore(lastDiscordAt.plusSeconds(60))) return false;
        return true;
    }

    private static String discordSig(String activity, int world, int players)
    { return activity + "|" + world + "|" + players; }

    private static int clamp(int v, int min, int max) { return Math.min(Math.max(v, min), max); }

    private void tryUpdateOverlayOptimistically(int world, int players)
    {
        if (world <= 0) return;
        List<WorldCount> copy = new ArrayList<>(latestWorlds);
        boolean found = false;
        for (WorldCount wc : copy)
        {
            if (wc.world == world) { wc.count = players; found = true; break; }
        }
        if (!found)
        {
            WorldCount wc = new WorldCount();
            wc.world = world;
            wc.count = players;
            copy.add(0, wc);
        }
        latestWorlds = copy;
    }

    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged e)
    {
        if (!CFG_GROUP.equals(e.getGroup())) return;
        if ("fetchIntervalSec".equals(e.getKey()))
        {
            if (NETWORK_BEACONS_ENABLED) scheduleFetch();
        }
    }
}
