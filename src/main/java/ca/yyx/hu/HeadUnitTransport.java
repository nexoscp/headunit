
// Headunit app Transport: USB / Wifi

package ca.yyx.hu;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;

import ca.yyx.hu.decoder.AudioDecoder;
import ca.yyx.hu.decoder.MicRecorder;
import ca.yyx.hu.decoder.VideoDecoder;
import ca.yyx.hu.usb.UsbDeviceCompat;

public class HeadUnitTransport {

    private final AudioDecoder mAudioDecoder;
    private final MicRecorder mMicRecorder;
    private final Listener mListener;
    private final VideoDecoder mVideoDecoder;
    private tra_thread m_tra_thread;                                 // Transport Thread

    private UsbManager mUsbMgr;
    private UsbDevice mUsbDeviceConnected;

    public static final int AA_CH_CTR = 0;                               // Sync with HeadUnitTransport.java, hu_aap.h and hu_aap.c:aa_type_array[]
    private static final int AA_CH_TOU = 3;
    private static final int AA_CH_SEN = 1;
    private static final int AA_CH_VID = 2;

    public boolean isAapStarted() {
        return aap_running;
    }

    public boolean isDeviceRunning(UsbDeviceCompat deviceCompat) {
        if (aap_running) {
            return deviceCompat.getWrappedDevice().equals(mUsbDeviceConnected);
        }
        return false;
    }

    interface Listener {
        void onAapStart();
    }

    public HeadUnitTransport(UsbManager usbManager, AudioDecoder audioDecoder, VideoDecoder videoDecoder, Listener listener) {
        mAudioDecoder = audioDecoder;
        mVideoDecoder = videoDecoder;
        mMicRecorder = new MicRecorder();
        mUsbMgr = usbManager;
        mListener = listener;
    }

    // Native API:

    static {
        System.loadLibrary("hu_jni");
    }

    // Java_ca_yyx_hu_HeadUnitTransport_native_1aa_1cmd
    private static native int native_aa_cmd(int cmd_len, byte[] cmd_buf, int res_len, byte[] res_buf);

    private byte[] mic_audio_buf = new byte[MicRecorder.MIC_BUFFER_SIZE];
    private int mic_audio_len = 0;

    private byte[] test_buf = null;
    private int test_len = 0;
    private boolean test_rdy = false;

    public void test_send(byte[] buf, int len) {
        test_buf = buf;
        test_len = len;
        test_rdy = true;
    }

    private boolean m_mic_active = false;
    private boolean touch_sync = true;//      // Touch sync times out within 200 ms on second touch with TCP for some reason.

    private final class tra_thread extends Thread {                       // Main Transport Thread
        private volatile boolean m_stopping = false;                        // Set true when stopping

        public tra_thread() {
            super("tra_thread");                                             // Name thread
        }

        @Override
        public void run() {
            int ret = 0;

            while (!m_stopping) {                                            // Loop until stopping...

                if (test_rdy && test_len > 0 && test_buf != null) {
                    ret = aa_cmd_send(test_len, test_buf, 0, null);            // Send test command
                    test_rdy = false;
                }


                if (touch_sync && !m_stopping && new_touch && len_touch > 0 && ba_touch != null) {
                    ret = aa_cmd_send(len_touch, ba_touch, 0, null);           // Send touch event
                    ba_touch = null;
                    new_touch = false;
                    continue;
                }

                if (!m_stopping && ret >= 0 && m_mic_active) {                 // If Mic active...
                    mic_audio_len = mMicRecorder.mic_audio_read(mic_audio_buf, MicRecorder.MIC_BUFFER_SIZE);
                    if (mic_audio_len >= 64) {                                    // If we read at least 64 bytes of audio data
                        byte[] ba_mic = new byte[14 + mic_audio_len];//0x02, 0x0b, 0x03, 0x00,
                        ba_mic[0] = MicRecorder.AA_CH_MIC;// Mic channel
                        ba_mic[1] = 0x0b;  // Flag filled here
                        ba_mic[2] = 0x00;  // 2 bytes Length filled here
                        ba_mic[3] = 0x00;
                        ba_mic[4] = 0x00;  // Message Type = 0 for data, OR 32774 for Stop w/mandatory 0x08 int and optional 0x10 int (senderprotocol/aq -> com.google.android.e.b.ca)
                        ba_mic[5] = 0x00;

                        long ts = android.os.SystemClock.elapsedRealtime();        // ts = Timestamp (uptime) in microseconds
                        int ctr = 0;
                        for (ctr = 7; ctr >= 0; ctr--) {                           // Fill 8 bytes backwards
                            ba_mic[6 + ctr] = (byte) (ts & 0xFF);
                            ts = ts >> 8;
                        }

                        for (ctr = 0; ctr < mic_audio_len; ctr++)                  // Copy mic PCM data
                            ba_mic[14 + ctr] = mic_audio_buf[ctr];

                        //Utils.hex_dump ("MIC: ", ba_mic, 64);
                        ret = aa_cmd_send(14 + mic_audio_len, ba_mic, 0, null);    // Send mic audio

                    } else if (mic_audio_len > 0)
                        Utils.loge("!!!!!!!");
                }

                if (!m_stopping && ret >= 0)
                    ret = aa_cmd_send(0, null, 0, null);                         // Null message to just poll
                if (ret < 0)
                    m_stopping = true;

            }

            byebye_send();                                                   // If m_stopping then... Byebye
        }

        public void quit() {
            m_stopping = true;                                                // Terminate thread
        }
    }

    public int jni_aap_start() {                                         // Start JNI Android Auto Protocol and Main Thread. Called only by usb_attach_handler(), usb_force() & HeadUnitActivity.wifi_long_start()

        mListener.onAapStart();
        byte[] cmd_buf = {121, -127, 2};                                   // Start Request w/ m_ep_in_addr, m_ep_out_addr
        cmd_buf[1] = (byte) m_ep_in_addr;
        cmd_buf[2] = (byte) m_ep_out_addr;
        int ret = aa_cmd_send(cmd_buf.length, cmd_buf, 0, null);           // Send: Start USB & AA

        if (ret == 0) {                                                     // If started OK...
            Utils.logd("aa_cmd_send ret: " + ret);
            aap_running = true;
            m_tra_thread = new tra_thread();
            m_tra_thread.start();                                            // Create and start Transport Thread
        } else
            Utils.loge("aa_cmd_send ret:" + ret);
        return (ret);
    }

    private int byebye_send() {                                          // Send Byebye request. Called only by stop (), tra_thread:run()
        Utils.logd("");
        byte[] cmd_buf = {AA_CH_CTR, 0x0b, 0, 0, 0, 0x0f, 0x08, 0};          // Byebye Request:  000b0004000f0800  00 0b 00 04 00 0f 08 00
        int ret = aa_cmd_send(cmd_buf.length, cmd_buf, 0, null);           // Send
        Utils.ms_sleep(100);                                              // Wait a bit for response
        return (ret);
    }

    private byte[] fixed_cmd_buf = new byte[256];
    private byte[] fixed_res_buf = new byte[65536 * 16];

    // Send AA packet/HU command/mic audio AND/OR receive video/output audio/audio notifications
    private int aa_cmd_send(int cmd_len, byte[] cmd_buf, int res_len, byte[] res_buf) {
        //synchronized (this) {

        if (cmd_buf == null || cmd_len <= 0) {
            cmd_buf = fixed_cmd_buf;//new byte [256];// {0};                                  // Allocate fake buffer to avoid problems
            cmd_len = 0;//cmd_buf.length;
        }
        if (res_buf == null || res_len <= 0) {
            res_buf = fixed_res_buf;//new byte [65536 * 16];  // Seen up to 151K so far; leave at 1 megabyte
            res_len = res_buf.length;
        }

        int ret = native_aa_cmd(cmd_len, cmd_buf, res_len, res_buf);       // Send a command (or null command)

        if (ret == 1) {                                                     // If mic stop...
            Utils.logd("Microphone Stop");
            m_mic_active = false;
            mMicRecorder.mic_audio_stop();
            return (0);
        } else if (ret == 2) {                                                // Else if mic start...
            Utils.logd("Microphone Start");
            m_mic_active = true;
            return (0);
        } else if (ret == 3) {                                                // Else if audio stop...
            Utils.logd("Audio Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AUD);
            return (0);
        } else if (ret == 4) {                                                // Else if audio1 stop...
            Utils.logd("Audio1 Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AU1);
            return (0);
        } else if (ret == 5) {                                                // Else if audio2 stop...
            Utils.logd("Audio2 Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AU2);
            return (0);
        } else if (ret > 0) {                                                 // Else if audio or video returned...
            handleMedia(res_buf, ret);
        }
        return (ret);
    }

    public void handleMedia(byte[] buffer, int size) {
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.limit(size);
        bb.position(0);

        if (VideoDecoder.isH246Video(buffer)) {
            mVideoDecoder.decode(bb);
        } else {
            mAudioDecoder.decode(bb);
        }
    }

    private long last_move_ms = 0;
    private int len_touch = 0;
    private boolean new_touch = false;
    private byte[] ba_touch = null;

    public void touch_send(byte action, int x, int y) {                  // Touch event send. Called only by HeadUnitActivity:touch_send()

        if (!aap_running) {
            return;
        }

        int err_ctr = 0;
        while (new_touch) {                                                 // While previous touch not yet processed...
            if (err_ctr++ % 5 == 0)
                Utils.logd("Waiting for new_touch = false");
            if (err_ctr > 20) {
                Utils.loge("Timeout waiting for new_touch = false");
                boolean touch_timeout_force = true;
                if (touch_timeout_force)
                    new_touch = false;
                else
                    return;
            }
            Utils.ms_sleep(50);                                             // Wait a bit
        }

        ba_touch = new byte[]{AA_CH_TOU, 0x0b, 0x00, 0x00, -128, 0x01, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0, 0, 0, 0x1a, 0x0e, 0x0a, 0x08, 0x08, 0x2e, 0, 0x10, 0x2b, 0, 0x18, 0x00, 0x10, 0x00, 0x18, 0x00};

        long ts = android.os.SystemClock.elapsedRealtime() * 1000000L;   // Timestamp in nanoseconds = microseconds x 1,000,000

        int siz_arr = 0;

        int idx = 1 + 6 + Utils.varint_encode(ts, ba_touch, 1 + 6);          // Encode timestamp

        ba_touch[idx++] = 0x1a;                                           // Value 3 array
        int size1_idx = idx;                                                // Save size1_idx
        ba_touch[idx++] = 0x0a;                                           // Default size 10
//
        ba_touch[idx++] = 0x0a;                                           // Contents = 1 array
        int size2_idx = idx;                                                // Save size2_idx
        ba_touch[idx++] = 0x04;                                           // Default size 4
        //
        ba_touch[idx++] = 0x08;                                             // Value 1
        siz_arr = Utils.varint_encode(x, ba_touch, idx);                 // Encode X
        idx += siz_arr;
        ba_touch[size1_idx] += siz_arr;                                    // Adjust array sizes for X
        ba_touch[size2_idx] += siz_arr;

        ba_touch[idx++] = 0x10;                                             // Value 2
        siz_arr = Utils.varint_encode(y, ba_touch, idx);                 // Encode Y
        idx += siz_arr;
        ba_touch[size1_idx] += siz_arr;                                    // Adjust array sizes for Y
        ba_touch[size2_idx] += siz_arr;

        ba_touch[idx++] = 0x18;                                             // Value 3
        ba_touch[idx++] = 0x00;                                           // Encode Z ?
        //
        ba_touch[idx++] = 0x10;
        ba_touch[idx++] = 0x00;

        ba_touch[idx++] = 0x18;
        ba_touch[idx++] = action;
//
        int ret = 0;
        if (!touch_sync) {                                               // If allow sending from different thread
            ret = aa_cmd_send(idx, ba_touch, 0, null);                     // Send directly
            return;
        }

        len_touch = idx;
        new_touch = true;
    }


    // USB:


    private boolean aap_running = false;

    public void stop() {                                       // USB Transport Stop. Called only by HeadUnitActivity.all_stop()
        Utils.logd("  aap_running: " + aap_running);

        if (aap_running) {
            aap_running = false;
        }

        byebye_send();                                                     // Terminate AA Protocol with ByeBye

        if (m_tra_thread != null) {                                           // If Transport Thread...
            m_tra_thread.quit();                                             // Terminate Transport Thread using it's quit() API
        }

        usb_disconnect();                                                  // Disconnect
    }

    public void usb_select_device(UsbDeviceCompat deviceCompat) {
        if (mUsbDeviceConnected != null) {
            usb_disconnect();
        }

        Utils.logd("Try connect");
        // Attempt to connect
        if (usb_connect(deviceCompat.getWrappedDevice())) {
            // If connected now, or was connected already...
            Utils.logd("Connected to device, start JNI");
            // Start JNI Android Auto Protocol and Main Thread
            jni_aap_start();
        } else {
            Utils.logd("Not connected");
        }
    }

    // "Android", "Android Open Automotive Protocol", "Description", "VersionName", "https://developer.android.com/auto/index.html", "62skidoo"
    // "Android", "Android Auto", "Description", "VersionName", "https://developer.android.com/auto/index.html", "62skidoo"

    // Indexes for strings sent by the host via ACC_REQ_SEND_STRING:
    private static final int ACC_IDX_MAN = 0;
    private static final int ACC_IDX_MOD = 1;
    //private static final int ACC_IDX_DES = 2;
    //private static final int ACC_IDX_VER = 3;
    //private static final int ACC_IDX_URI = 4;
    //private static final int ACC_IDX_SER = 5;
    // OAP Control requests:
    private static final int ACC_REQ_GET_PROTOCOL = 51;
    private static final int ACC_REQ_SEND_STRING = 52;
    private static final int ACC_REQ_START = 53;
    //  private static final int ACC_REQ_REGISTER_HID       = 54;
    //  private static final int ACC_REQ_UNREGISTER_HID     = 55;
    //  private static final int ACC_REQ_SET_HID_REPORT_DESC= 56;
    //  private static final int ACC_REQ_SEND_HID_EVENT     = 57;
    //  private static final int ACC_REQ_AUDIO              = 58;

    private UsbDeviceConnection mUsbDeviceConnection = null;                   // USB Device connection
    private UsbInterface mUsbInterface = null;                   // USB Interface
    private UsbEndpoint m_usb_ep_in = null;                   // USB Input  endpoint
    private UsbEndpoint m_usb_ep_out = null;                   // USB Output endpoint
    private int m_ep_in_addr = -1;                                      // Input  endpoint Value  129
    private int m_ep_out_addr = -1;                                      // Output endpoint Value    2

    private boolean usb_connect(UsbDevice device) {                         // Attempt to connect. Called only by usb_attach_handler() & presets_select()
        mUsbDeviceConnection = null;

        int ret = usb_open(device);                                        // Open USB device & claim interface
        if (ret < 0) {                                                      // If error...
            usb_disconnect();                                                // Ensure state is disconnected
            return false;                                                           // Done
        }

        int dev_vend_id = device.getVendorId();                            // mVendorId=2996               HTC
        int dev_prod_id = device.getProductId();                           // mProductId=1562              OneM8

        // If in accessory mode...
        if (dev_vend_id == UsbDeviceCompat.USB_VID_GOO && (dev_prod_id == UsbDeviceCompat.USB_PID_ACC || dev_prod_id == UsbDeviceCompat.USB_PID_ACC_ADB)) {
            ret = acc_mode_endpoints_set();                                  // Set Accessory mode Endpoints
            if (ret < 0) {                                                    // If error...
                usb_disconnect();                                              // Ensure state is disconnected
                return false;
            }
            mUsbDeviceConnected = device;
            return true;

        }
        // Else if not in accessory mode...
        acc_mode_switch(mUsbDeviceConnection);
        usb_disconnect();                                                  // Ensure state is disconnected
        return false;
    }

    private void usb_disconnect() {                                           // Release interface and close USB device connection. Called only by usb_disconnect()
        if (mUsbDeviceConnected != null) {
            Utils.logd(mUsbDeviceConnected.toString());
        }
        m_usb_ep_in = null;                                               // Input  EP
        m_usb_ep_out = null;                                               // Output EP
        m_ep_in_addr = -1;                                                 // Input  endpoint Value
        m_ep_out_addr = -1;                                                 // Output endpoint Value

        if (mUsbDeviceConnection != null) {
            boolean bret = false;
            if (mUsbInterface != null) {
                bret = mUsbDeviceConnection.releaseInterface(mUsbInterface);
            }
            if (bret) {
                Utils.logd("OK releaseInterface()");
            } else {
                Utils.loge("Error releaseInterface()");
            }

            mUsbDeviceConnection.close();                                        //
        }
        mUsbDeviceConnection = null;
        mUsbInterface = null;
        mUsbDeviceConnected = null;
    }

    private int usb_open(UsbDevice device) {                             // Open USB device connection & claim interface. Called only by usb_connect()
        try {
            if (mUsbDeviceConnection == null) {
                mUsbDeviceConnection = mUsbMgr.openDevice(device);                 // Open device for connection
            }
        } catch (Throwable e) {
            Utils.loge(e);                                  // java.lang.IllegalArgumentException: device /dev/bus/usb/001/019 does not exist or is restricted
            return -1;
        }

        if (mUsbDeviceConnection == null) {
            Utils.loge("Could not obtain usb connection for device: " + device);
            return (-1);                                                      // Done error
        }
        Utils.logd("Established connection: " + mUsbDeviceConnection);

        try {
            int iface_cnt = device.getInterfaceCount();
            if (iface_cnt <= 0) {
                Utils.loge("iface_cnt: " + iface_cnt);
                return (-1);                                                    // Done error
            }
            Utils.logd("iface_cnt: " + iface_cnt);
            mUsbInterface = device.getInterface(0);                            // java.lang.ArrayIndexOutOfBoundsException: length=0; index=0

            if (!mUsbDeviceConnection.claimInterface(mUsbInterface, true)) {        // Claim interface, if error...   true = take from kernel
                Utils.loge("Error claiming interface");
                return (-1);
            }
            Utils.logd("Success claiming interface");
        } catch (Throwable e) {
            Utils.loge("Throwable: " + e);           // Nexus 7 2013:    Throwable: java.lang.ArrayIndexOutOfBoundsException: length=0; index=0
            return (-1);                                                      // Done error
        }
        return (0);                                                         // Done success
    }

    private int acc_mode_endpoints_set() {                               // Set Accessory mode Endpoints. Called only by usb_connect()
        Utils.logd("Check accessory endpoints");
        m_usb_ep_in = null;                                               // Setup bulk endpoints.
        m_usb_ep_out = null;
        m_ep_in_addr = -1;     // 129
        m_ep_out_addr = -1;     // 2

        for (int i = 0; i < mUsbInterface.getEndpointCount(); i++) {        // For all USB endpoints...
            UsbEndpoint ep = mUsbInterface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {              // If IN
                if (m_usb_ep_in == null) {                                      // If Bulk In not set yet...
                    m_ep_in_addr = ep.getAddress();
                    Utils.logd(String.format("Bulk IN m_ep_in_addr: %d  %d", m_ep_in_addr, i));
                    m_usb_ep_in = ep;                                             // Set Bulk In
                }
            } else {                                                            // Else if OUT...
                if (m_usb_ep_out == null) {                                     // If Bulk Out not set yet...
                    m_ep_out_addr = ep.getAddress();
                    Utils.logd(String.format("Bulk OUT m_ep_out_addr: %d  %d", m_ep_out_addr, i));
                    m_usb_ep_out = ep;                                            // Set Bulk Out
                }
            }
        }
        if (m_usb_ep_in == null || m_usb_ep_out == null) {
            Utils.loge("Unable to find bulk endpoints");
            return (-1);                                                      // Done error
        }

        Utils.logd("Connected have EPs");
        return (0);                                                         // Done success
    }

    private boolean acc_mode_switch(UsbDeviceConnection conn) {             // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        Utils.logd("Attempt acc");

        int len = 0;
        byte buffer[] = new byte[2];
        len = conn.controlTransfer(UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, 10000);
        if (len != 2) {
            Utils.loge("Error controlTransfer len: " + len);
            return false;
        }
        int acc_ver = (buffer[1] << 8) | buffer[0];                      // Get OAP / ACC protocol version
        Utils.logd("Success controlTransfer len: " + len + "  acc_ver: " + acc_ver);
        if (acc_ver < 1) {                                                  // If error or version too low...
            Utils.loge("No support acc");
            return false;
        }
        Utils.logd("acc_ver: " + acc_ver);

        // Send all accessory identification strings
        usb_acc_string_send(conn, ACC_IDX_MAN, Utils.str_MAN);            // Manufacturer
        usb_acc_string_send(conn, ACC_IDX_MOD, Utils.str_MOD);            // Model
        //usb_acc_string_send (conn, ACC_IDX_DES, Utils.str_DES);
        //usb_acc_string_send (conn, ACC_IDX_VER, Utils.str_VER);
        //usb_acc_string_send (conn, ACC_IDX_URI, Utils.str_URI);
        //usb_acc_string_send (conn, ACC_IDX_SER, Utils.str_SER);

        Utils.logd("Sending acc start");           // Send accessory start request. Device should re-enumerate as an accessory.
        len = conn.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, null, 0, 10000);
        if (len != 0) {
            Utils.loge("Error acc start");
            return false;
        }
        Utils.logd("OK acc start. Wait to re-enumerate...");
        return true;
    }

    // Send one accessory identification string.    Called only by acc_mode_switch()
    private void usb_acc_string_send(UsbDeviceConnection conn, int index, String string) {
        byte[] buffer = (string + "\0").getBytes();
        int len = conn.controlTransfer(UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, buffer, buffer.length, 10000);
        if (len != buffer.length) {
            Utils.loge("Error controlTransfer len: " + len + "  index: " + index + "  string: \"" + string + "\"");
        } else {
            Utils.logd("Success controlTransfer len: " + len + "  index: " + index + "  string: \"" + string + "\"");
        }
    }

    public void usb_force() {                                            // Called only by HeadUnitActivity:preset_select_lstnr:onClick()
        if (mUsbDeviceConnected != null) {
            usb_disconnect();
        }

        if (Utils.su_installed_get()) {
            String cmd = "setenforce 0 ; chmod -R 777 /dev/bus 1>/dev/null 2>/dev/null";
            Utils.sys_run(cmd, true);
        }

        m_ep_in_addr = 255;
        m_ep_out_addr = 0;  // USB Force
        jni_aap_start();
    }

}
