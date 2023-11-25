package org.biglybt.plugin.throttle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.RoundingMode;
//import java.util.HashMap;
//import java.util.Map;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.biglybt.core.internat.MessageText;
import com.biglybt.core.peer.PEPeerStats;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.DisplayFormatters;
import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
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
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;
import com.biglybt.pifimpl.local.peers.PeerStatsImpl;

public class Reciprocal implements Plugin {
	
	private final int bytes2megabytes = 1048576;
	private PluginInterface plugin_interface;
	private LoggerChannel logger;
	private List<String> priorPeerList = new ArrayList<String>();

	@Override
	public void initialize(PluginInterface _pi) throws PluginException {
		
		init(_pi);
	    configEventLogger();
	    
	    UIManager ui_manager = plugin_interface.getUIManager();
	    BasicPluginConfigModel config_model = ui_manager.createBasicPluginConfigModel("reciprocal.name");
	    
	    // UI parameters:
	    final BooleanParameter plugin_enable = config_model.addBooleanParameter2("reciprocal.enable", "reciprocal.enable", true);
	    final BooleanParameter plugin_debug = config_model.addBooleanParameter2("reciprocal.debug", "reciprocal.debug", false);
	    final IntParameter plugin_debug_level = config_model.addIntParameter2("reciprocal.debug_level", "reciprocal.debug_level", 1, 1, 5);
	    final IntParameter plugin_check_secs = config_model.addIntParameter2("reciprocal.check_secs", "reciprocal.check_secs", 30, 1, 3600);
	    final IntParameter plugin_ignoreMargin = config_model.addIntParameter2("reciprocal.ignore_margin", "reciprocal.ignore_margin", 100, 1, 1024);
	    final IntParameter plugin_ignoreIfSentBelow  = config_model.addIntParameter2("reciprocal.start_threshold", "reciprocal.start_threshold", 10, 1, 1024);
	    final StringParameter plugin_min_ul_rate = config_model.addStringParameter2("reciprocal.min_ul_rate", "reciprocal.min_ul_rate", "0.5");
	    final BooleanParameter plugin_adjust_on_new = config_model.addBooleanParameter2("reciprocal.adjust_on_new", "reciprocal.adjust_on_new", false);
	    
	    // floating point example:

	    ParameterListener float_checker = getFloatCheckerPl();
	    plugin_min_ul_rate.addListener(float_checker);
	    log(plugin_min_ul_rate);


	    plugin_enable.addEnabledOnSelection(plugin_debug);
	    plugin_enable.addEnabledOnSelection(plugin_check_secs);
	    plugin_enable.addEnabledOnSelection(plugin_ignoreMargin);
	    plugin_enable.addEnabledOnSelection(plugin_ignoreIfSentBelow);
	    plugin_enable.addEnabledOnSelection(plugin_min_ul_rate); // floating point example
	    plugin_enable.addEnabledOnSelection(plugin_adjust_on_new);
	    plugin_debug.addEnabledOnSelection(plugin_debug_level);
	    
	    plugin_interface.getUtilities().createTimer("processor", true).addPeriodicEvent(1000, new UTTimerEventPerformer() {
	    	private boolean was_enabled;
	    	private int tick_count = 0;

	    	public void perform(UTTimerEvent event) {
	    		boolean is_enabled = plugin_enable.getValue();
	    		boolean is_debug = plugin_debug.getValue();
//	    		boolean is_adjust_on_new = plugin_adjust_on_new.getValue();
	    		boolean isTimeToCheckAll = false;
	    		boolean newPeers = false;
	    		int debug_level = plugin_debug_level.getValue();

	    		if (tick_count == 0 || is_enabled != was_enabled) {
	    			logger.log("Enabled=" + is_enabled);
	    			was_enabled = is_enabled;
	    		}
	    		if (!is_enabled) return;
	    		
	    		// Timer based update
	    		if (tick_count % plugin_check_secs.getValue() == 0) isTimeToCheckAll = true;
	    		
	    		Download[] downloads = plugin_interface.getDownloadManager().getDownloads();
	    		List<String> newPeerList = new ArrayList<String>();
//	    		if (is_adjust_on_new) { // only bother to check if feature is on
//	    			// Determine if any new peers arrived since last clock tick.  There's a technical corner case where a peer on one download joins a different download and slips through the cracks, but the timer will still catch them - so not worth the added complexity
//	    			for (Download download: downloads) {
//	    				if (download.getState() == Download.ST_DOWNLOADING) {
//	    					PeerManager pm = download.getPeerManager();
//	    					if (pm == null) continue;
//	    					Peer[] peers = pm.getPeers();
//	    					for (Peer peer: peers) {
//	    						if (peer.isInteresting()) {
//	    							String peerIdHex = bytesToHex(peer.getId());
//	    							if (peerIdHex != null && peerIdHex.length() == 0) peerIdHex = null;
//	    							if (peerIdHex != null) {
//	    								newPeerList.add(peerIdHex);
//	    								if (!priorPeerList.contains(peerIdHex)) {
//	    									newPeerList.add(peerIdHex);
//	    									newPeers = true; // new arrival
//	    								}
//	    							}
//	    						}
//	    					}
//	    				}
//	    			}
//	    		}

	    		if (isTimeToCheckAll || newPeers) {
	    			boolean isDownloading = false;
	    			float ignoreIfSentBelow = plugin_ignoreIfSentBelow.getValue(); // in MB.  Don't bother controlling peer we haven't sent this amount of data to.
	    			float ignoreMargin = plugin_ignoreMargin.getValue(); // If our download margin exceeds this, allow unlimited upload speed
	    			float min_ul_rate = 0.5f;
	    			try {
	    				min_ul_rate = Float.parseFloat(plugin_min_ul_rate.getValue());
	    			} catch (Throwable e) {
	    				logger.log("Min upload rate invalid: " + plugin_min_ul_rate.getValue());
	    			}
	    			min_ul_rate = min_ul_rate * 1024; // convert to bytes

	    			for (Download download: downloads) {
    					PeerManager pm = download.getPeerManager();
    					if (pm == null) continue;
    					Peer[] peers = pm.getPeers();
    					
	    				if (download.getState() == Download.ST_DOWNLOADING) {
	    					isDownloading = true;
	    					for (Peer peer: peers) {
								String peerIdHex = bytesToHex(peer.getId());
								if (peerIdHex != null && peerIdHex.length() == 0) peerIdHex = null;
								String peerIp = peer.getIp();
	    						PeerStats stats = peer.getStats();
	    						if (stats instanceof PeerStatsImpl) {
	    							boolean isPeerNew = false;
//	    							if (is_adjust_on_new && peerIdHex != null) isPeerNew = newPeerList.contains(peerIdHex); // only bother to check if feature is on
	    							if (isTimeToCheckAll || (newPeers && isPeerNew)) {
	    								if (is_debug && debug_level > 3 && isPeerNew) {
	    									logger.log(peerIp + "	" + peerIdHex + "	This peer is new.");
	    									if (is_debug && debug_level > 4) logger.log("New peers list: " + newPeerList.toString());
	    								}
	    								adjustPeer(peer, stats, ignoreIfSentBelow, ignoreMargin, min_ul_rate, peerIp, peerIdHex, is_debug, debug_level);
	    							}
	    						}
	    					}
	    				} else { // Seeding or other
	    					for (Peer peer: peers) {
	    						PeerStats stats = peer.getStats();
	    						if (stats instanceof PeerStatsImpl) {
	    							PEPeerStats pe_stats = ((PeerStatsImpl) stats).getDelegate();
	    							pe_stats.setUploadRateLimitBytesPerSecond(0); // Set Unlimited upload bandwidth
	    						}
	    					}
	    				}
	    			}
	    			if (is_debug && !isDownloading) logger.log("No downloading currently occuring.");
	    		}
	    		tick_count++;
	    	}
	    });
	}
	
	private void adjustPeer(Peer peer, PeerStats stats, float ignoreIfSentBelow, float ignoreMargin, float min_ul_rate, String peerIp, String peerIdHex, boolean is_debug, int debug_level) {
		PEPeerStats pe_stats = ((PeerStatsImpl) stats).getDelegate();
		int upload_limit = 0; // note: -1 = disabled, 0 = infinity (no limit)
		if (peer.isInteresting()) {
//			long send_rate = pe_stats.getDataSendRate();
			long receiveRateSmoothed = pe_stats.getSmoothDataReceiveRate();
			float megabytesReceivedFromPeer = pe_stats.getTotalDataBytesReceived() / bytes2megabytes;
			float megabytesSentToPeer = pe_stats.getTotalDataBytesSent() / bytes2megabytes;
			float deltaTransmittedMb = megabytesReceivedFromPeer - megabytesSentToPeer;
			if (is_debug) logger.log(peerIp + "	" + peerIdHex + "	down-up delta = " + num2Str(deltaTransmittedMb, 2) + " MB, receiveRateSmoothed = " + num2Str(receiveRateSmoothed / 1024, 1) + " KB");

			if (megabytesSentToPeer > ignoreIfSentBelow && deltaTransmittedMb < ignoreMargin) { // peer communication has not just started AND is within the control range
				if (deltaTransmittedMb < 0) { // peer is in default.  Make them catch up.
					float severityFactor = (ignoreMargin + deltaTransmittedMb) / ignoreMargin;
					if (severityFactor < 0) severityFactor = 0f;
					if (severityFactor > 1) severityFactor = 1f;
					severityFactor = 1 - severityFactor; // invert range so that: 1 (most severe) to 0 (least severe)
					if (severityFactor > 0.99) upload_limit = -1; // completely disable uploading to any scumbags
					else {
						upload_limit = (int) (receiveRateSmoothed - (receiveRateSmoothed * severityFactor));
						if (upload_limit < min_ul_rate) upload_limit = Math.round(min_ul_rate); // prevent set rate from approaching zero, since 0 actually means 'unlimited'
					}
					if (is_debug && debug_level > 2) logger.log(peerIp + "	" + peerIdHex + "	Negative Scenario - severityFactor = " + num2Str(severityFactor, 2) + ", upload_limit = " + getRateString(upload_limit));
				} else { // Peer has given more than they've taken, but we still need to prevent them from running away with uploads
					float marginFactor = (ignoreMargin - deltaTransmittedMb) / ignoreMargin; // factor 0 to 1, where: 1 (requires greatest limiting) to 0 (requires least rate limiting)
					if (marginFactor < 0) marginFactor = 0f;
					if (marginFactor > 1) marginFactor = 1f;
					marginFactor = 1 - marginFactor; // invert the marginFactor result so that 1 = least rate limiting and 0 = most
					int uploadMargin = (int) (receiveRateSmoothed * marginFactor * 0.5); // peer's rate is permitted up to a max 50% of their download rate, based on the ratio of their current margin to the ignoreMargin.
					upload_limit = (int) (receiveRateSmoothed + uploadMargin);
					int minBytes = (int) (deltaTransmittedMb * 1024); // if peer with small net positive download is currently choking us or has slow connectivity, convert their MB margin into upstream KB rate credit.
					if (upload_limit < minBytes) {
						if (is_debug && debug_level > 4) {
							if (upload_limit < 500) logger.log(peerIp + "	" + peerIdHex + "	Proposed upload limit: " + upload_limit + " B/s is judged too low for peer with net postive contribution.  Using: " + getRateString(minBytes)); // special case because very low number gets interpreted as infinity
							else logger.log(peerIp + "	" + peerIdHex + "	Proposed upload limit: " + getRateString(upload_limit) + " is judged too low for peer with net postive contribution.  Using: " + getRateString(minBytes));
						}
						upload_limit = minBytes;
					}
					if (is_debug && debug_level > 3) logger.log(peerIp + "	" + peerIdHex + "	Low Positive Scenario - marginFactor = " + num2Str(marginFactor, 2) + ", uploadMargin = " + getRateString(uploadMargin) + ", upload_limit = " + getRateString(upload_limit));
				}
			} else if (is_debug && debug_level > 3) logger.log(peerIp + "	" + peerIdHex + "	Unlimited uploading for this peer.");
		}
		int existing = pe_stats.getUploadRateLimitBytesPerSecond();
		if (existing != upload_limit) {
			logger.log(peerIp + "	Action: Setting upload limit to " + getRateString(upload_limit) + ".  Prior limit was " + getRateString(existing) + ".");
			pe_stats.setUploadRateLimitBytesPerSecond(upload_limit);
		}
	}
	    
	private void init(PluginInterface _pi) {
	    plugin_interface = _pi;
	    logger = plugin_interface.getLogger().getTimeStampedChannel("Reciprocal"); // Config logger
	}
	
	private void configEventLogger() {
	    LocaleUtilities loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
	    loc_utils.integrateLocalisedMessageBundle("org.biglybt.plugin.throttle.messages.Messages");
	    String plugin_name = loc_utils.getLocalisedMessageText("reciprocal.name");
	    
	    UIManager ui_manager = plugin_interface.getUIManager();
	    final BasicPluginViewModel view_model = ui_manager.createBasicPluginViewModel(plugin_name);
	    view_model.getActivity().setVisible(false);
	    view_model.getProgress().setVisible(false);
	    view_model.setConfigSectionID("reciprocal.name");	    
	    
	    logger.addListener(new LoggerChannelListener() {
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
	    });
	}
	
	private ParameterListener getFloatCheckerPl() {
		return new ParameterListener() {
	    	private Map <Object, String> last_valid = new HashMap <Object, String>();

	    	public void parameterChanged(Parameter param) {
	    		StringParameter sp = (StringParameter) param;
	    		String str = sp.getValue();
	    		try {
	    			Float.parseFloat(str);
	    			last_valid.put(param, str);
	    		} catch (Throwable e) {
	    			String last = last_valid.get(param);
	    			if (last == null) last = "0.0";
	    			if (!last.equals(str)) sp.setValue(last);
	    		}
	    		log(sp);
	    	};
	    };
	}
	
	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
	private static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for (int j = 0; j < bytes.length; j++) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = HEX_ARRAY[v >>> 4];
	        hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
	    }
	    return new String(hexChars);
	}
	
	private String getRateString(int rate) {
		if (rate == -1) return (MessageText.getString("MyTorrents.items.UpSpeedLimit.disabled"));
		else if (rate == 0) return (Constants.INFINITY_STRING);
		else return (DisplayFormatters.formatByteCountToKiBEtcPerSec(rate));
	}
	
//	private String getRateString(long rate) {
//		return (DisplayFormatters.formatByteCountToKiBEtcPerSec(rate));
//	}
	
	private String num2Str(float value, int places) {
		if (places < 0) places = 0;
		String decimalPattern = "";
		for (int i=0; i < places; i++) decimalPattern = decimalPattern + "#";
		if (places == 0) decimalPattern = "#";
		else decimalPattern = "#." + decimalPattern;
		DecimalFormat df = new DecimalFormat(decimalPattern);
		df.setRoundingMode(RoundingMode.HALF_UP);
		return df.format(value);
	}
	
	private void log(StringParameter sp) {
		logger.log("'" + sp.getLabelText() + "' set to " + sp.getValue());
	}

}
