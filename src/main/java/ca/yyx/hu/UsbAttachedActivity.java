package ca.yyx.hu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Toast;

import ca.yyx.hu.aap.AapService;
import ca.yyx.hu.usb.UsbDeviceCompat;
import ca.yyx.hu.usb.UsbModeSwitch;
import ca.yyx.hu.utils.AppLog;
import ca.yyx.hu.utils.IntentUtils;
import ca.yyx.hu.utils.Settings;

/**
 * @author algavris
 * @date 30/05/2016.
 */
public class UsbAttachedActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppLog.logd("USB Intent: " + getIntent());

        UsbDevice device = IntentUtils.getDevice(getIntent());
        if (device == null) {
            AppLog.loge("No USB device");
            finish();
            return;
        }

        if (App.get(this).transport().isAlive()) {
            AppLog.loge("Thread already running");
            finish();
            return;
        }

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            AppLog.loge("Usb in accessory mode");
            startService(AapService.createIntent(device, this));
            finish();
            return;
        }

        UsbDeviceCompat deviceCompat = new UsbDeviceCompat(device);
        Settings settings = new Settings(this);
        if (!settings.isConnectingDevice(deviceCompat)) {
            AppLog.logd("Skipping device " + deviceCompat.getUniqueName());
            finish();
            return;
        }

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbModeSwitch usbMode = new UsbModeSwitch(usbManager);
        AppLog.logd("Switching USB device to accessory mode " + deviceCompat.getUniqueName());
        Toast.makeText(this, "Switching USB device to accessory mode " + deviceCompat.getUniqueName(), Toast.LENGTH_SHORT).show();
        if (usbMode.switchMode(device)) {
            Toast.makeText(this, "Success", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show();
        }

        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        UsbDevice device = IntentUtils.getDevice(getIntent());
        if (device == null) {
            AppLog.loge("No USB device");
            finish();
            return;
        }

        AppLog.logd(UsbDeviceCompat.getUniqueName(device));

        if (!App.get(this).transport().isAlive()) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.loge("Usb in accessory mode");
                startService(AapService.createIntent(device, this));
            }
        } else {
            AppLog.loge("Thread already running");
        }

        finish();
    }
}
