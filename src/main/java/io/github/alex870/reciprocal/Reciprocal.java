package io.github.alex870.reciprocal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import com.biglybt.core.internat.MessageText;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.DisplayFormatters;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.peers.PeerStats;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.UTTimer;
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;
import com.biglybt.pifimpl.local.peers.PeerStatsImpl;

/**
 * Reciprocal: while a torrent is downloading, limit each peer's upload rate according to
 * how much they have given back, so the share-ratio budget is not spent on leechers.
 * All limits are lifted once the torrent stops downloading.
 */
public class Reciprocal implements UnloadablePlugin {

	private static final String MESSAGES = "io.github.alex870.reciprocal.messages.Messages";
	private static final String DEFAULT_MIN_RATE = "0.5";

	// Log levels (setting "reciprocal.log_level"). Errors and enable/disable are always logged.
	static final int LOG_ACTIONS = 1;  // every limit change
	static final int LOG_PEERS = 2;    // one line per peer per pass
	static final int LOG_DEFICIT = 3;  // detail for peers in deficit
	static final int LOG_SURPLUS = 4;  // detail for peers in surplus / unlimited
	static final int LOG_TRACE = 5;    // everything

	private PluginInterface plugin_interface;
	private LoggerChannel logger;
	private LoggerChannelListener log_listener;
	private BasicPluginConfigModel config_model;
	private BasicPluginViewModel view_model;
	private UTTimer timer;

	private BooleanParameter plugin_enable;
	private IntParameter plugin_log_level;
	private IntParameter plugin_check_secs;
	private IntParameter plugin_ignore_margin;
	private IntParameter plugin_start_threshold;
	private StringParameter plugin_min_ul_rate;
	private BooleanParameter plugin_disable_extreme;

	private final LimitController<Peer> limits = new LimitController<Peer>(new LimitController.Access<Peer>() {
		public int getUploadLimit(Peer peer) {
			return peer.getStats().getUploadRateLimit();
		}

		public void setUploadLimit(Peer peer, int limit) {
			peer.getStats().setUploadRateLimit(limit);
		}
	});

	private boolean was_enabled;
	private long tick_count;
	private final Set<String> reported_errors = new HashSet<String>();

	@Override
	public void initialize(PluginInterface pi) throws PluginException {
		plugin_interface = pi;
		logger = pi.getLogger().getTimeStampedChannel("Reciprocal");

		LocaleUtilities loc_utils = pi.getUtilities().getLocaleUtilities();
		loc_utils.integrateLocalisedMessageBundle(MESSAGES);

		createLogView(loc_utils.getLocalisedMessageText("reciprocal.name"));
		createConfig(pi.getUIManager());

		was_enabled = plugin_enable.getValue();
		logger.log("Enabled=" + was_enabled);

		timer = pi.getUtilities().createTimer("Reciprocal", true);
		timer.addPeriodicEvent(1000, new UTTimerEventPerformer() {
			public void perform(UTTimerEvent event) {
				tick();
			}
		});
	}

	@Override
	public void unload() throws PluginException {
		if (timer != null) {
			timer.destroy();
			timer = null;
		}
		int lifted = limits.releaseAll();
		if (lifted > 0) logger.log("Unloading: lifted " + lifted + " upload limit(s).");
		if (log_listener != null) logger.removeListener(log_listener);
		if (config_model != null) config_model.destroy();
		if (view_model != null) view_model.destroy();
	}

	private void createConfig(UIManager ui_manager) {
		migrateLegacySettings();

		config_model = ui_manager.createBasicPluginConfigModel("reciprocal.name");
		plugin_enable = config_model.addBooleanParameter2("reciprocal.enable", "reciprocal.enable", true);
		plugin_log_level = config_model.addIntParameter2("reciprocal.log_level", "reciprocal.log_level", 0, 0, LOG_TRACE);
		plugin_check_secs = config_model.addIntParameter2("reciprocal.check_secs", "reciprocal.check_secs", 30, 1, 3600);
		plugin_ignore_margin = config_model.addIntParameter2("reciprocal.ignore_margin", "reciprocal.ignore_margin", 100, 1, 1024);
		plugin_start_threshold = config_model.addIntParameter2("reciprocal.start_threshold", "reciprocal.start_threshold", 10, 1, 1024);
		plugin_min_ul_rate = config_model.addStringParameter2("reciprocal.min_ul_rate", "reciprocal.min_ul_rate", DEFAULT_MIN_RATE);
		plugin_disable_extreme = config_model.addBooleanParameter2("reciprocal.disable_extreme", "reciprocal.disable_extreme", false);

		plugin_min_ul_rate.addListener(new ParameterListener() {
			private String last_valid = ReciprocityPolicy.parseMinRateKiB(plugin_min_ul_rate.getValue()) != null
					? plugin_min_ul_rate.getValue() : DEFAULT_MIN_RATE;

			public void parameterChanged(Parameter param) {
				String value = plugin_min_ul_rate.getValue();
				if (ReciprocityPolicy.parseMinRateKiB(value) != null) {
					last_valid = value;
					logger.log("'" + plugin_min_ul_rate.getLabelText() + "' set to " + value);
				} else {
					logger.log("Invalid minimum upload rate '" + value + "' (must be a number > 0); keeping " + last_valid);
					if (!last_valid.equals(value)) plugin_min_ul_rate.setValue(last_valid);
				}
			}
		});

		plugin_enable.addEnabledOnSelection(plugin_log_level);
		plugin_enable.addEnabledOnSelection(plugin_check_secs);
		plugin_enable.addEnabledOnSelection(plugin_ignore_margin);
		plugin_enable.addEnabledOnSelection(plugin_start_threshold);
		plugin_enable.addEnabledOnSelection(plugin_min_ul_rate);
		plugin_enable.addEnabledOnSelection(plugin_disable_extreme);
	}

	/** 0.2.x had separate "debug" on/off and "debug_level" 1-5 settings; fold them into log_level. */
	private void migrateLegacySettings() {
		PluginConfig pc = plugin_interface.getPluginconfig();
		if (pc.hasPluginParameter("reciprocal.log_level")) return;
		if (pc.getPluginBooleanParameter("reciprocal.debug", false)) {
			int old_level = pc.getPluginIntParameter("reciprocal.debug_level", 1);
			pc.setPluginParameter("reciprocal.log_level", Math.max(LOG_PEERS, Math.min(LOG_TRACE, old_level)));
		}
	}

	private void createLogView(String plugin_name) {
		view_model = plugin_interface.getUIManager().createBasicPluginViewModel(plugin_name);
		view_model.getActivity().setVisible(false);
		view_model.getProgress().setVisible(false);
		view_model.setConfigSectionID("reciprocal.name");

		log_listener = new LoggerChannelListener() {
			public void messageLogged(int type, String content) {
				view_model.getLogArea().appendText(content + "\n");
			}

			public void messageLogged(String str, Throwable error) {
				if (str.length() > 0) view_model.getLogArea().appendText(str + "\n");
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				error.printStackTrace(pw);
				pw.flush();
				view_model.getLogArea().appendText(sw.toString() + "\n");
			}
		};
		logger.addListener(log_listener);
	}

	// ---------------------------------------------------------------- timer

	private void tick() {
		try {
			boolean is_enabled = plugin_enable.getValue();
			if (is_enabled != was_enabled) {
				logger.log("Enabled=" + is_enabled);
				was_enabled = is_enabled;
				if (!is_enabled) {
					int lifted = limits.releaseAll();
					if (lifted > 0) logger.log("Disabled: lifted " + lifted + " upload limit(s).");
				}
				tick_count = 0; // run a full pass straight away when re-enabled
			}
			if (!is_enabled) return;

			if (tick_count++ % plugin_check_secs.getValue() == 0) checkAll();
		} catch (Throwable e) {
			reportOnce("tick", "Unexpected error in timer", e);
		}
	}

	private void checkAll() {
		int log_level = plugin_log_level.getValue();
		ReciprocityPolicy.Settings settings = currentSettings();
		Set<Peer> connected = new HashSet<Peer>();
		boolean any_downloading = false;

		for (Download download : plugin_interface.getDownloadManager().getDownloads()) {
			PeerManager pm = download.getPeerManager();
			if (pm == null) continue;
			boolean downloading = download.getState() == Download.ST_DOWNLOADING;
			any_downloading |= downloading;

			for (Peer peer : pm.getPeers()) {
				connected.add(peer);
				try {
					if (downloading) {
						adjustPeer(peer, settings, log_level);
					} else if (limits.release(peer) && log_level >= LOG_ACTIONS) {
						// Not downloading (complete, seeding, ...): lift any limit we set.
						logger.log(peer.getIp() + "\tAction: lifted upload limit (torrent not downloading).");
					}
				} catch (Throwable e) {
					reportOnce("peer:" + e.getClass().getName(), "Error adjusting peer " + safeIp(peer), e);
				}
			}
		}
		limits.retainOnly(connected);
		if (log_level >= LOG_PEERS && !any_downloading) logger.log("No downloading currently occurring.");
	}

	private ReciprocityPolicy.Settings currentSettings() {
		Float min_kib = ReciprocityPolicy.parseMinRateKiB(plugin_min_ul_rate.getValue());
		if (min_kib == null) min_kib = ReciprocityPolicy.parseMinRateKiB(DEFAULT_MIN_RATE);
		return ReciprocityPolicy.Settings.ofMegabytes(
				plugin_start_threshold.getValue(),
				plugin_ignore_margin.getValue(),
				ReciprocityPolicy.kibToBps(min_kib),
				plugin_disable_extreme.getValue());
	}

	private void adjustPeer(Peer peer, ReciprocityPolicy.Settings settings, int log_level) {
		PeerStats stats = peer.getStats();
		long sent = stats.getTotalSent();
		long received = stats.getTotalReceived();
		long rate = smoothedReceiveRate(stats);

		ReciprocityPolicy.Decision d = ReciprocityPolicy.decide(sent, received, rate, peer.isInteresting(), settings);

		if (log_level >= LOG_PEERS && d.reason != ReciprocityPolicy.Reason.NOT_INTERESTING) {
			logger.log(peerLabel(peer, log_level) + "\tdown-up delta = " + formatMb(received - sent)
					+ ", receive rate = " + DisplayFormatters.formatByteCountToKiBEtcPerSec(rate));
		}
		if (log_level >= LOG_DEFICIT && (d.reason == ReciprocityPolicy.Reason.DEFICIT || d.reason == ReciprocityPolicy.Reason.EXTREME_DEFICIT)) {
			logger.log(peerLabel(peer, log_level) + "\tDeficit - severity = " + format2(d.factor) + ", upload limit = " + rateString(d.limit));
		}
		if (log_level >= LOG_SURPLUS) {
			if (d.reason == ReciprocityPolicy.Reason.SURPLUS) {
				logger.log(peerLabel(peer, log_level) + "\tSurplus - margin factor = " + format2(d.factor) + ", upload limit = " + rateString(d.limit));
			} else if (d.reason == ReciprocityPolicy.Reason.FREE_START || d.reason == ReciprocityPolicy.Reason.WELL_AHEAD) {
				logger.log(peerLabel(peer, log_level) + "\tUnlimited uploading for this peer (" + d.reason + ").");
			}
		}

		int before = stats.getUploadRateLimit();
		LimitController.Result result = limits.apply(peer, d.limit);
		if (log_level >= LOG_ACTIONS && (result == LimitController.Result.SET || result == LimitController.Result.RESTORED)) {
			logger.log(peer.getIp() + "\tAction: Setting upload limit to " + rateString(d.limit) + ".  Prior limit was " + rateString(before) + ".");
		} else if (log_level >= LOG_TRACE && result == LimitController.Result.SKIPPED_FOREIGN) {
			logger.log(peer.getIp() + "\tSkipped: upload limit " + rateString(before) + " was set outside Reciprocal.");
		}
	}

	/**
	 * The public API only exposes an unsmoothed rate; prefer the smoothed one when the
	 * internal implementation is available, since a single snapshot is taken per pass.
	 */
	private static long smoothedReceiveRate(PeerStats stats) {
		if (stats instanceof PeerStatsImpl) return ((PeerStatsImpl) stats).getDelegate().getSmoothDataReceiveRate();
		return stats.getDownloadAverage();
	}

	// ---------------------------------------------------------------- helpers

	private void reportOnce(String key, String message, Throwable e) {
		boolean first;
		synchronized (reported_errors) {
			first = reported_errors.add(key);
		}
		if (first) logger.log(message + " (further errors of this kind are not logged)", e);
	}

	private static String safeIp(Peer peer) {
		try {
			return peer.getIp();
		} catch (Throwable e) {
			return "?";
		}
	}

	private static String peerLabel(Peer peer, int log_level) {
		if (log_level < LOG_TRACE) return peer.getIp();
		return peer.getIp() + "\t" + bytesToHex(peer.getId());
	}

	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	static String bytesToHex(byte[] bytes) {
		if (bytes == null) return "-";
		char[] hex = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hex[j * 2] = HEX_ARRAY[v >>> 4];
			hex[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hex);
	}

	private static String rateString(int rate) {
		if (rate == ReciprocityPolicy.DISABLED) return MessageText.getString("MyTorrents.items.UpSpeedLimit.disabled");
		if (rate == ReciprocityPolicy.UNLIMITED) return Constants.INFINITY_STRING;
		return DisplayFormatters.formatByteCountToKiBEtcPerSec(rate);
	}

	private static String formatMb(long bytes) {
		return format2(bytes / (1024d * 1024d)) + " MB";
	}

	private static String format2(double v) {
		return String.format("%.2f", v);
	}
}
