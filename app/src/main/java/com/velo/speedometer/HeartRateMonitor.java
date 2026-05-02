package com.velo.speedometer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE Heart Rate Monitor — standard Bluetooth HRP profile.
 *
 * Service  0x180D  Heart Rate
 * Char     0x2A37  Heart Rate Measurement
 * Desc     0x2902  CCCD (enable notifications)
 *
 * Battery Service  0x180F
 * Char             0x2A19  Battery Level (0–100 %)
 *
 * Works with Polar H10, Wahoo TICKR, Garmin HRM-Pro, and any BLE HRM.
 * Device address is persisted in SharedPreferences ("hr_device_address").
 *
 * Usage:
 *   monitor.connect(address)    — connect to saved address
 *   monitor.startScan(cb)       — scan for nearby HRM devices
 *   monitor.stopScan()
 *   monitor.disconnect()
 *   monitor.getLastBpm()        — latest value, 0 if not connected
 *   monitor.getLastBattery()    — battery %, -1 if not yet read
 */
public class HeartRateMonitor {

    // ── BLE UUIDs ─────────────────────────────────────────────────────────────
    public static final UUID HR_SERVICE_UUID      = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    public static final UUID HR_CHAR_UUID         = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Battery Service (standard BLE profile, supported by most HRMs)
    public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_LEVEL_UUID   = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface HrListener {
        void onHeartRate(int bpm);
        void onConnectionState(boolean connected);
        /** Called once after connection when battery level is successfully read. */
        default void onBatteryLevel(int percent) {}
    }

    public interface ScanListener {
        void onDeviceFound(String name, String address, int rssi);
        void onScanFinished();
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Context  context;
    private HrListener     hrListener;
    private BluetoothGatt  gatt;
    private volatile int   lastBpm      = 0;
    private volatile int   lastBattery  = -1;   // -1 = not yet read
    private volatile boolean connected  = false;
    private final Handler  main = new Handler(Looper.getMainLooper());

    // Scan
    private BluetoothLeScanner scanner;
    private ScanCallback        scanCb;
    private final Handler       scanHandler = new Handler(Looper.getMainLooper());
    private static final long   SCAN_TIMEOUT_MS = 15_000L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public HeartRateMonitor(Context ctx, HrListener listener) {
        this.context    = ctx.getApplicationContext();
        this.hrListener = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int     getLastBpm()     { return lastBpm; }
    public boolean isConnected()    { return connected; }
    /** Returns battery level 0–100, or -1 if not yet read from sensor. */
    public int     getLastBattery() { return lastBattery; }

    public void connect(String address) {
        if (address == null || address.isEmpty()) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        lastBattery = -1;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { /* invalid address or no BT */ }
    }

    public void disconnect() {
        lastBpm     = 0;
        connected   = false;
        lastBattery = -1;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    public void startScan(ScanListener listener) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onScanFinished(); return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onScanFinished(); return; }

        ScanFilter filter   = new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(HR_SERVICE_UUID.toString()))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        List<String> found = new ArrayList<>();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                String addr = result.getDevice().getAddress();
                if (found.contains(addr)) return;
                found.add(addr);
                String name = result.getDevice().getName();
                if (name == null) name = "Unknown HRM";
                String finalName = name;
                main.post(() -> listener.onDeviceFound(finalName, addr, result.getRssi()));
            }
        };

        try {
            scanner.startScan(List.of(filter), settings, scanCb);
        } catch (Exception e) { listener.onScanFinished(); return; }

        scanHandler.postDelayed(() -> {
            stopScan();
            listener.onScanFinished();
        }, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        scanHandler.removeCallbacksAndMessages(null);
        if (scanner != null && scanCb != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        }
        scanCb = null;
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                g.discoverServices();
                main.post(() -> { if (hrListener != null) hrListener.onConnectionState(true); });
            } else {
                connected = false;
                lastBpm   = 0;
                main.post(() -> { if (hrListener != null) hrListener.onConnectionState(false); });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            // Subscribe to HR notifications
            BluetoothGattService svc = g.getService(HR_SERVICE_UUID);
            if (svc != null) {
                BluetoothGattCharacteristic ch = svc.getCharacteristic(HR_CHAR_UUID);
                if (ch != null) {
                    g.setCharacteristicNotification(ch, true);
                    BluetoothGattDescriptor desc = ch.getDescriptor(CCCD_UUID);
                    if (desc != null) {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(desc);
                        // Battery read is triggered in onDescriptorWrite after CCCD is written
                        // to avoid concurrent GATT operations
                    }
                }
            }
        }

        /**
         * After CCCD write completes, chain the battery level read.
         * GATT operations must be serialized — never overlap them.
         */
        @Override
        public void onDescriptorWrite(BluetoothGatt g,
                                      BluetoothGattDescriptor descriptor, int status) {
            // Read battery level once CCCD is configured
            BluetoothGattService batSvc = g.getService(BATTERY_SERVICE_UUID);
            if (batSvc != null) {
                BluetoothGattCharacteristic batChar = batSvc.getCharacteristic(BATTERY_LEVEL_UUID);
                if (batChar != null) {
                    g.readCharacteristic(batChar);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic ch) {
            int bpm = parseHrm(ch.getValue());
            if (bpm <= 0) return;
            lastBpm = bpm;
            main.post(() -> { if (hrListener != null) hrListener.onHeartRate(bpm); });
        }

        /**
         * Called when battery level read completes (API < 33).
         * Deprecated in API 33 but still called on older devices.
         */
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g,
                                         BluetoothGattCharacteristic ch, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            handleCharacteristicValue(ch, ch.getValue());
        }

        /**
         * Called when battery level read completes (API 33+).
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt g,
                                         BluetoothGattCharacteristic ch,
                                         byte[] value, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            handleCharacteristicValue(ch, value);
        }

        private void handleCharacteristicValue(BluetoothGattCharacteristic ch, byte[] value) {
            if (!BATTERY_LEVEL_UUID.equals(ch.getUuid())) return;
            if (value == null || value.length == 0) return;
            int percent = value[0] & 0xFF;
            lastBattery = percent;
            main.post(() -> { if (hrListener != null) hrListener.onBatteryLevel(percent); });
        }
    };

    /**
     * BT HRP spec: byte[0] flags; bit0=0 → HR is uint8 in byte[1];
     *                              bit0=1 → HR is uint16 in byte[1..2] LE.
     */
    private static int parseHrm(byte[] data) {
        if (data == null || data.length < 2) return 0;
        if ((data[0] & 0x01) == 0) return data[1] & 0xFF;
        if (data.length < 3) return 0;
        return ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
    }
}
