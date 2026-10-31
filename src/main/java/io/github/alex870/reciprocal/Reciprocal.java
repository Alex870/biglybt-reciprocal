package io.github.alex870.reciprocal;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.download.DownloadPeerListener;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.peers.PeerManagerEvent;
import com.biglybt.pif.peers.PeerManagerListener2;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.StringListParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.ui.tables.TableCell;
import com.biglybt.pif.ui.tables.TableCellRefreshListener;
import com.biglybt.pif.ui.tables.TableColumn;
import com.biglybt.pif.ui.tables.TableColumnCreationListener;
import com.biglybt.pif.ui.tables.TableManager;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.UTTimer;
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;

/**
 * Reciprocal: while a torrent is downloading, limit each peer's upload rate according to
 * how much they have given back, so the share-ratio budget is not spent on leechers.
 * All limits are lifted once the torrent stops downloading.
 * <p>
 * This class wires BiglyBT to the {@link Engine}: settings, timer, peer/download events,
 * ledger persistence and UI columns.
 */
public class Reciprocal implements UnloadablePlugin {

	private static final String MESSAGES = "io.github.alex870.reciprocal.messages.Messages";
	private static final String DEFAULT_MIN_RATE = "0.5";
	private static final String LEDGER_FILE = "ledger.txt";
	private static final long LEDGER_SAVE_INTERVAL_MS = 10 * 60 * 1000;

	private static final String COL_PEER_STATE = "reciprocal.peer.state";
	private static final String COL_PEER_BALANCE = "reciprocal.peer.balance";
	private static final String COL_TORRENT_BUDGET = "reciprocal.torrent.budget";

	private PluginInterface plugin_interface;
	private LocaleUtilities loc_utils;
	private LoggerChannel logger;
	private LoggerChannelListener log_listener;
	private BasicPluginConfigModel config_model;
	private BasicPluginViewModel view_model;
	private UTTimer timer;
	private UTTimerEvent timer_event;
	private int timer_secs;

	private BooleanParameter p_enable;
	private IntParameter p_log_level;
	private IntParameter p_check_secs;
	private IntParameter p_start_threshold;
	private IntParameter p_start_threshold_pct;
	private IntParameter p_ignore_margin;
	private IntParameter p_ignore_margin_pct;
	private StringParameter p_min_ul_rate;
	private BooleanParameter p_disable_extreme;
	private StringListParameter p_non_interesting;
	private IntParameter p_free_cap_pct;
	private BooleanParameter p_exempt_lan;
	private IntParameter p_ledger_ttl_hours;
	private BooleanParameter p_ledger_persist;
	private BooleanParameter p_adjust_on_connect;
	private BooleanParameter p_dry_run;
	private IntParameter p_budget_pct;

	private Engine<Peer> engine;
	private volatile boolean enabled;
	private volatile boolean unloaded;
	private long last_ledger_save;
	private final Set<String> reported_errors = new HashSet<String>();

	// Listener registrations, so unload can remove them.
	private DownloadManagerListener dm_listener;
	private final Map<Download, DownloadPeerListener> peer_listeners = new HashMap<Download, DownloadPeerListener>();
	private final Map<Download, DownloadListener> state_listeners = new HashMap<Download, DownloadListener>();
	private final Map<PeerManager, PeerManagerListener2> pm_listeners = new HashMap<PeerManager, PeerManagerListener2>();
	private final List<TableColumn> columns = new ArrayList<TableColumn>();

	@Override
	public void initialize(PluginInterface pi) throws PluginException {
		plugin_interface = pi;
		logger = pi.getLogger().getTimeStampedChannel("Reciprocal");

		loc_utils = pi.getUtilities().getLocaleUtilities();
		loc_utils.integrateLocalisedMessageBundle(MESSAGES);

		createLogView(loc_utils.getLocalisedMessageText("reciprocal.name"));
		createConfig(pi.getUIManager());

		engine = new Engine<Peer>(new BiglyPeerAccess(), new Engine.Log() {
			public boolean on(int level) {
				return p_log_level.getValue() >= level;
			}

			public void log(String message) {
				logger.log(message);
			}
		});
		engine.setConfig(readConfig());
		loadLedger();

		enabled = p_enable.getValue();
		logger.log("Enabled=" + enabled);

		timer = pi.getUtilities().createTimer("Reciprocal", true);
		scheduleTimer();

		registerEventListeners();
		registerColumns(pi.getUIManager().getTableManager());
	}

	@Override
	public void unload() throws PluginException {
		unloaded = true;
		if (timer != null) {
			timer.destroy();
			timer = null;
		}
		unregisterEventListeners();
		for (TableColumn c : columns) {
			try {
				c.remove();
			} catch (Throwable ignored) {
				// UI may already be gone
			}
		}
		TableManager tm = plugin_interface.getUIManager().getTableManager();
		tm.unregisterColumn(Peer.class, COL_PEER_STATE);
		tm.unregisterColumn(Peer.class, COL_PEER_BALANCE);
		tm.unregisterColumn(Download.class, COL_TORRENT_BUDGET);

		int lifted = engine.releaseAll();
		if (lifted > 0) logger.log("Unloading: lifted " + lifted + " upload limit(s).");
		saveLedger();
		if (log_listener != null) logger.removeListener(log_listener);
		if (config_model != null) config_model.destroy();
		if (view_model != null) view_model.destroy();
	}

	// ---------------------------------------------------------------- settings

	private void createConfig(UIManager ui_manager) {
		migrateLegacySettings();

		BasicPluginConfigModel m = config_model = ui_manager.createBasicPluginConfigModel("reciprocal.name");
		p_enable = m.addBooleanParameter2("reciprocal.enable", "reciprocal.enable", true);
		p_log_level = m.addIntParameter2("reciprocal.log_level", "reciprocal.log_level", 0, 0, Engine.LOG_TRACE);
		p_check_secs = m.addIntParameter2("reciprocal.check_secs", "reciprocal.check_secs", 30, 1, 3600);
		p_dry_run = m.addBooleanParameter2("reciprocal.dry_run", "reciprocal.dry_run", false);

		p_start_threshold = m.addIntParameter2("reciprocal.start_threshold", "reciprocal.start_threshold", 10, 0, 1024);
		p_start_threshold_pct = m.addIntParameter2("reciprocal.start_threshold_pct", "reciprocal.start_threshold_pct", 1, 0, 100);
		p_ignore_margin = m.addIntParameter2("reciprocal.ignore_margin", "reciprocal.ignore_margin", 100, 1, 1024 * 100);
		p_ignore_margin_pct = m.addIntParameter2("reciprocal.ignore_margin_pct", "reciprocal.ignore_margin_pct", 0, 0, 100);
		p_min_ul_rate = m.addStringParameter2("reciprocal.min_ul_rate", "reciprocal.min_ul_rate", DEFAULT_MIN_RATE);
		p_disable_extreme = m.addBooleanParameter2("reciprocal.disable_extreme", "reciprocal.disable_extreme", false);

		String[] ni_values = { "min_rate", "allowance", "unlimited" };
		String[] ni_labels = new String[ni_values.length];
		for (int i = 0; i < ni_values.length; i++) {
			ni_labels[i] = loc_utils.getLocalisedMessageText("reciprocal.non_interesting." + ni_values[i]);
		}
		p_non_interesting = m.addStringListParameter2("reciprocal.non_interesting", "reciprocal.non_interesting", ni_values, ni_labels, "min_rate");
		p_free_cap_pct = m.addIntParameter2("reciprocal.free_cap_pct", "reciprocal.free_cap_pct", 20, 0, 1000);
		p_exempt_lan = m.addBooleanParameter2("reciprocal.exempt_lan", "reciprocal.exempt_lan", true);

		p_adjust_on_connect = m.addBooleanParameter2("reciprocal.adjust_on_connect", "reciprocal.adjust_on_connect", true);
		p_ledger_ttl_hours = m.addIntParameter2("reciprocal.ledger_ttl_hours", "reciprocal.ledger_ttl_hours", 24, 1, 24 * 90);
		p_ledger_persist = m.addBooleanParameter2("reciprocal.ledger_persist", "reciprocal.ledger_persist", false);
		p_budget_pct = m.addIntParameter2("reciprocal.budget_pct", "reciprocal.budget_pct", 120, 1, 10000);

		m.createGroup("reciprocal.group.general", new Parameter[] { p_enable, p_log_level, p_check_secs, p_dry_run });
		m.createGroup("reciprocal.group.limits", new Parameter[] { p_start_threshold, p_start_threshold_pct,
				p_ignore_margin, p_ignore_margin_pct, p_min_ul_rate, p_disable_extreme });
		m.createGroup("reciprocal.group.budget", new Parameter[] { p_non_interesting, p_free_cap_pct, p_exempt_lan, p_budget_pct });
		m.createGroup("reciprocal.group.ledger", new Parameter[] { p_adjust_on_connect, p_ledger_ttl_hours, p_ledger_persist });

		p_min_ul_rate.addListener(new ParameterListener() {
			private String last_valid = ReciprocityPolicy.parseMinRateKiB(p_min_ul_rate.getValue()) != null
					? p_min_ul_rate.getValue() : DEFAULT_MIN_RATE;

			public void parameterChanged(Parameter param) {
				String value = p_min_ul_rate.getValue();
				if (ReciprocityPolicy.parseMinRateKiB(value) != null) {
					last_valid = value;
					logger.log("'" + p_min_ul_rate.getLabelText() + "' set to " + value);
				} else {
					logger.log("Invalid minimum upload rate '" + value + "' (must be a number > 0); keeping " + last_valid);
					if (!last_valid.equals(value)) p_min_ul_rate.setValue(last_valid);
				}
			}
		});

		ParameterListener config_changed = new ParameterListener() {
			public void parameterChanged(Parameter param) {
				if (engine != null) engine.setConfig(readConfig());
			}
		};
		for (Parameter p : new Parameter[] { p_start_threshold, p_start_threshold_pct, p_ignore_margin, p_ignore_margin_pct,
				p_min_ul_rate, p_disable_extreme, p_non_interesting, p_free_cap_pct, p_exempt_lan, p_ledger_ttl_hours,
				p_adjust_on_connect, p_dry_run }) {
			p.addListener(config_changed);
		}

		p_enable.addListener(new ParameterListener() {
			public void parameterChanged(Parameter param) {
				setEnabled(p_enable.getValue());
			}
		});
		p_check_secs.addListener(new ParameterListener() {
			public void parameterChanged(Parameter param) {
				scheduleTimer();
			}
		});

		for (Parameter p : new Parameter[] { p_log_level, p_check_secs, p_dry_run, p_start_threshold, p_start_threshold_pct,
				p_ignore_margin, p_ignore_margin_pct, p_min_ul_rate, p_disable_extreme, p_non_interesting, p_free_cap_pct,
				p_exempt_lan, p_adjust_on_connect, p_ledger_ttl_hours, p_ledger_persist, p_budget_pct }) {
			p_enable.addEnabledOnSelection(p);
		}
	}

	private Engine.Config readConfig() {
		Engine.Config c = new Engine.Config();
		c.startThresholdMb = p_start_threshold.getValue();
		c.startThresholdPct = p_start_threshold_pct.getValue();
		c.ignoreMarginMb = p_ignore_margin.getValue();
		c.ignoreMarginPct = p_ignore_margin_pct.getValue();
		Float min_kib = ReciprocityPolicy.parseMinRateKiB(p_min_ul_rate.getValue());
		if (min_kib == null) min_kib = ReciprocityPolicy.parseMinRateKiB(DEFAULT_MIN_RATE);
		c.minRateBps = ReciprocityPolicy.kibToBps(min_kib);
		c.disableAtExtremeDeficit = p_disable_extreme.getValue();
		c.nonInteresting = ReciprocityPolicy.NonInteresting.parse(p_non_interesting.getValue());
		c.freeUploadCapPct = p_free_cap_pct.getValue();
		c.exemptLan = p_exempt_lan.getValue();
		c.ledgerTtlMs = p_ledger_ttl_hours.getValue() * 3600L * 1000L;
		c.adjustOnConnect = p_adjust_on_connect.getValue();
		c.dryRun = p_dry_run.getValue();
		return c;
	}

	/**
	 * 0.2.x had separate "debug" on/off and "debug_level" 1-5 settings; fold them into log_level.
	 * The old "adjust_on_new" setting never worked, so it is not carried over.
	 */
	private void migrateLegacySettings() {
		PluginConfig pc = plugin_interface.getPluginconfig();
		if (pc.hasPluginParameter("reciprocal.log_level")) return;
		if (pc.getPluginBooleanParameter("reciprocal.debug", false)) {
			int old_level = pc.getPluginIntParameter("reciprocal.debug_level", 1);
			pc.setPluginParameter("reciprocal.log_level", Math.max(Engine.LOG_PEERS, Math.min(Engine.LOG_TRACE, old_level)));
		}
	}

	private void setEnabled(boolean value) {
		if (value == enabled) return;
		enabled = value;
		logger.log("Enabled=" + value);
		if (!value) {
			int lifted = engine.releaseAll();
			if (lifted > 0) logger.log("Disabled: lifted " + lifted + " upload limit(s).");
		} else {
			runPass();
		}
	}

	// ---------------------------------------------------------------- timer

	private synchronized void scheduleTimer() {
		if (timer == null) return;
		int secs = Math.max(1, p_check_secs.getValue());
		if (timer_event != null && secs == timer_secs) return;
		if (timer_event != null) timer_event.cancel();
		timer_secs = secs;
		timer_event = timer.addPeriodicEvent(secs * 1000L, new UTTimerEventPerformer() {
			public void perform(UTTimerEvent event) {
				runPass();
			}
		});
	}

	private void runPass() {
		if (unloaded || !enabled) return;
		try {
			long now = System.currentTimeMillis();
			Set<Peer> connected = new HashSet<Peer>();
			Set<String> active = new HashSet<String>();
			boolean any_downloading = false;

			for (Download download : plugin_interface.getDownloadManager().getDownloads()) {
				PeerManager pm = download.getPeerManager();
				if (pm == null) continue;
				Engine.Torrent t = torrentOf(download);
				if (t == null) continue;
				active.add(t.hash);
				any_downloading |= t.downloading;
				List<Peer> peers = Arrays.asList(pm.getPeers());
				connected.addAll(peers);
				try {
					engine.pass(t, peers, now);
				} catch (Throwable e) {
					reportOnce("pass:" + e.getClass().getName(), "Error processing " + t.name, e);
				}
			}
			engine.endPass(connected, active, now);
			if (p_log_level.getValue() >= Engine.LOG_PEERS && !any_downloading) logger.log("No downloading currently occurring.");
			updateStatus();
			if (now - last_ledger_save >= LEDGER_SAVE_INTERVAL_MS) saveLedger();
		} catch (Throwable e) {
			reportOnce("tick", "Unexpected error in timer", e);
		}
	}

	private Engine.Torrent torrentOf(Download download) {
		Torrent torrent = download.getTorrent();
		if (torrent == null) return null; // e.g. magnet link still fetching metadata
		byte[] hash = torrent.getHash();
		if (hash == null) return null;
		return new Engine.Torrent(bytesToHex(hash), download.getName(), torrent.getSize(),
				download.getState() == Download.ST_DOWNLOADING, download.isComplete());
	}

	// ---------------------------------------------------------------- events (Phase 4)

	private void registerEventListeners() {
		dm_listener = new DownloadManagerListener() {
			public void downloadAdded(Download download) {
				watchDownload(download);
			}

			public void downloadRemoved(Download download) {
				unwatchDownload(download);
			}
		};
		plugin_interface.getDownloadManager().addListener(dm_listener, true);
	}

	private void unregisterEventListeners() {
		if (dm_listener != null) plugin_interface.getDownloadManager().removeListener(dm_listener, false);
		List<Download> downloads;
		synchronized (peer_listeners) {
			downloads = new ArrayList<Download>(peer_listeners.keySet());
		}
		for (Download d : downloads) unwatchDownload(d);
		List<PeerManager> pms;
		synchronized (pm_listeners) {
			pms = new ArrayList<PeerManager>(pm_listeners.keySet());
		}
		for (PeerManager pm : pms) unwatchPeerManager(pm);
	}

	private void watchDownload(final Download download) {
		DownloadListener state_listener = new DownloadListener() {
			public void stateChanged(Download d, int old_state, int new_state) {
				if (!enabled || old_state != Download.ST_DOWNLOADING || new_state == Download.ST_DOWNLOADING) return;
				try {
					Engine.Torrent t = torrentOf(d);
					PeerManager pm = d.getPeerManager();
					if (t != null && pm != null) engine.onTorrentNotDownloading(t, Arrays.asList(pm.getPeers()));
				} catch (Throwable e) {
					reportOnce("state", "Error handling download state change", e);
				}
			}

			public void positionChanged(Download d, int oldPosition, int newPosition) {}
		};
		DownloadPeerListener peer_listener = new DownloadPeerListener() {
			public void peerManagerAdded(Download d, PeerManager pm) {
				watchPeerManager(d, pm);
			}

			public void peerManagerRemoved(Download d, PeerManager pm) {
				unwatchPeerManager(pm);
			}
		};
		synchronized (peer_listeners) {
			if (peer_listeners.containsKey(download)) return;
			peer_listeners.put(download, peer_listener);
			state_listeners.put(download, state_listener);
		}
		download.addListener(state_listener);
		download.addPeerListener(peer_listener);
	}

	private void unwatchDownload(Download download) {
		DownloadPeerListener pl;
		DownloadListener sl;
		synchronized (peer_listeners) {
			pl = peer_listeners.remove(download);
			sl = state_listeners.remove(download);
		}
		if (pl != null) download.removePeerListener(pl);
		if (sl != null) download.removeListener(sl);
		PeerManager pm = download.getPeerManager();
		if (pm != null) unwatchPeerManager(pm);
	}

	private void watchPeerManager(final Download download, PeerManager pm) {
		PeerManagerListener2 l = new PeerManagerListener2() {
			public void eventOccurred(PeerManagerEvent event) {
				int type = event.getType();
				if (type != PeerManagerEvent.ET_PEER_ADDED && type != PeerManagerEvent.ET_PEER_REMOVED) return;
				if (!enabled || unloaded) return;
				try {
					Peer peer = event.getPeer();
					Engine.Torrent t = torrentOf(download);
					if (peer == null || t == null) return;
					long now = System.currentTimeMillis();
					if (type == PeerManagerEvent.ET_PEER_ADDED) engine.onPeerAdded(t, peer, now);
					else engine.onPeerRemoved(t, peer, now);
				} catch (Throwable e) {
					reportOnce("event:" + e.getClass().getName(), "Error handling peer event", e);
				}
			}
		};
		synchronized (pm_listeners) {
			if (pm_listeners.containsKey(pm)) return;
			pm_listeners.put(pm, l);
		}
		pm.addListener(l);
	}

	private void unwatchPeerManager(PeerManager pm) {
		PeerManagerListener2 l;
		synchronized (pm_listeners) {
			l = pm_listeners.remove(pm);
		}
		if (l != null) pm.removeListener(l);
	}

	// ---------------------------------------------------------------- ledger persistence

	private File ledgerFile() {
		return plugin_interface.getPluginconfig().getPluginUserFile(LEDGER_FILE);
	}

	private void loadLedger() {
		if (!p_ledger_persist.getValue()) return;
		try {
			int n = engine.loadLedger(ledgerFile(), System.currentTimeMillis());
			if (n > 0) logger.log("Ledger: loaded " + n + " peer record(s).");
		} catch (Throwable e) {
			reportOnce("ledger-load", "Could not load ledger", e);
		}
	}

	private void saveLedger() {
		last_ledger_save = System.currentTimeMillis();
		if (!p_ledger_persist.getValue()) return;
		try {
			engine.saveLedger(ledgerFile());
		} catch (Throwable e) {
			reportOnce("ledger-save", "Could not save ledger", e);
		}
	}

	// ---------------------------------------------------------------- UI (Phase 5)

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

	private void updateStatus() {
		Engine.SessionStats s = engine.sessionStats();
		String text = (p_dry_run.getValue() ? "DRY RUN · " : "")
				+ "Uploaded this session to: deficit peers " + Engine.mb(s.sentToDeficit)
				+ ", surplus peers " + Engine.mb(s.sentToSurplus)
				+ ", unlimited " + Engine.mb(s.sentUnlimited)
				+ " · reconnects recognised " + s.reconnectsRecognised
				+ " · ledger " + engine.ledgerSize() + " peer(s)";
		view_model.getStatus().setText(text);
	}

	private void registerColumns(TableManager tm) {
		tm.registerColumn(Peer.class, COL_PEER_STATE, new TableColumnCreationListener() {
			public void tableColumnCreated(TableColumn column) {
				setupColumn(column, TableColumn.ALIGN_LEAD, 150);
				column.addCellRefreshListener(new TableCellRefreshListener() {
					public void refresh(TableCell cell) {
						refreshPeerState(cell);
					}
				});
			}
		});
		tm.registerColumn(Peer.class, COL_PEER_BALANCE, new TableColumnCreationListener() {
			public void tableColumnCreated(TableColumn column) {
				setupColumn(column, TableColumn.ALIGN_TRAIL, 80);
				column.addCellRefreshListener(new TableCellRefreshListener() {
					public void refresh(TableCell cell) {
						refreshPeerBalance(cell);
					}
				});
			}
		});
		tm.registerColumn(Download.class, COL_TORRENT_BUDGET, new TableColumnCreationListener() {
			public void tableColumnCreated(TableColumn column) {
				setupColumn(column, TableColumn.ALIGN_LEAD, 220);
				column.addCellRefreshListener(new TableCellRefreshListener() {
					public void refresh(TableCell cell) {
						refreshTorrentBudget(cell);
					}
				});
			}
		});
	}

	private void setupColumn(TableColumn column, int align, int width) {
		column.setAlignment(align);
		column.setPosition(TableColumn.POSITION_LAST);
		column.setWidth(width);
		column.setType(TableColumn.TYPE_TEXT_ONLY);
		synchronized (columns) {
			columns.add(column);
		}
	}

	private void refreshPeerState(TableCell cell) {
		Object ds = cell.getDataSource();
		Engine.PeerStatus st = ds instanceof Peer && engine != null ? engine.status((Peer) ds) : null;
		if (st == null || st.reason == null) {
			cell.setText("");
			cell.setSortValue(-2L);
			return;
		}
		String text = st.reason.label;
		if (st.reason.isThrottled()) text += " · " + Engine.rate(st.limit);
		if (!st.applied) text += p_dry_run.getValue() ? " (dry run)" : " (not applied)";
		cell.setText(text);
		cell.setSortValue(st.reason.isThrottled() ? (long) st.limit : Long.MAX_VALUE);
	}

	private void refreshPeerBalance(TableCell cell) {
		Object ds = cell.getDataSource();
		Engine.PeerStatus st = ds instanceof Peer && engine != null ? engine.status((Peer) ds) : null;
		if (st == null) {
			cell.setText("");
			cell.setSortValue(Long.MIN_VALUE);
			return;
		}
		cell.setText((st.balance >= 0 ? "+" : "") + Engine.mb(st.balance));
		cell.setSortValue(st.balance);
		cell.setToolTip("Received minus sent, across all connections from this address (MB)");
	}

	private void refreshTorrentBudget(TableCell cell) {
		Object ds = cell.getDataSource();
		if (!(ds instanceof Download) || engine == null) {
			cell.setText("");
			return;
		}
		Download d = (Download) ds;
		Torrent torrent = d.getTorrent();
		if (torrent == null || d.isComplete() || torrent.getSize() <= 0) {
			cell.setText("");
			cell.setSortValue(-1L);
			return;
		}
		double size = torrent.getSize();
		double up_ratio = d.getStats().getUploaded() / size;
		double done = d.getStats().getCompleted() / 1000d;
		double budget = p_budget_pct.getValue() / 100d;
		StringBuilder text = new StringBuilder();
		text.append(Engine.f2(up_ratio)).append("× of ").append(Engine.f2(budget)).append("× at ")
				.append(Math.round(done * 100)).append("%");
		Engine.TorrentSummary s = engine.summary(bytesToHex(torrent.getHash()));
		if (s != null && s.freeUploadCap != Long.MAX_VALUE) {
			text.append(" · free ").append(Math.round(100d * s.unreciprocated / Math.max(1, s.freeUploadCap))).append("%");
		}
		if (s != null) text.append(" · ").append(s.throttled).append("/").append(s.peers).append(" limited");
		cell.setText(text.toString());
		// Projected upload at completion vs budget: > 1 means on track to exceed the budget.
		double projected = done > 0.01 ? up_ratio / done : up_ratio;
		cell.setSortValue((float) (projected / budget));
		if (projected > budget) cell.setForegroundToErrorColor();
		else cell.setForeground(null);
		cell.setToolTip("Uploaded while downloading, as a multiple of torrent size, vs your budget of "
				+ Engine.f2(budget) + "×.\nProjected at completion: " + Engine.f2(projected) + "×."
				+ (s == null ? "" : "\nUnreciprocated upload: " + Engine.mb(s.unreciprocated)
						+ (s.freeUploadCap == Long.MAX_VALUE ? "" : " of free-upload cap " + Engine.mb(s.freeUploadCap))
						+ "\nPeers in deficit: " + s.deficit + ", in surplus: " + s.surplus));
	}

	// ---------------------------------------------------------------- BiglyBT peer access

	/** Reads peers through BiglyBT's public plugin API only. */
	private static final class BiglyPeerAccess implements Engine.PeerAccess<Peer> {
		public String ip(Peer peer) {
			return peer.getIp();
		}

		public long sent(Peer peer) {
			return peer.getStats().getTotalSent();
		}

		public long received(Peer peer) {
			return peer.getStats().getTotalReceived();
		}

		public long currentReceiveRate(Peer peer) {
			return peer.getStats().getDownloadAverage();
		}

		public boolean interesting(Peer peer) {
			return peer.isInteresting();
		}

		public boolean lanLocal(Peer peer) {
			return peer.isLANLocal();
		}

		public int getUploadLimit(Peer peer) {
			return peer.getStats().getUploadRateLimit();
		}

		public void setUploadLimit(Peer peer, int limit) {
			peer.getStats().setUploadRateLimit(limit);
		}
	}

	// ---------------------------------------------------------------- helpers

	private void reportOnce(String key, String message, Throwable e) {
		boolean first;
		synchronized (reported_errors) {
			first = reported_errors.add(key);
		}
		if (first) logger.log(message + " (further errors of this kind are not logged)", e);
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
}
