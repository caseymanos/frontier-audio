#!/bin/bash

# =====================================================
# Android VAD Benchmarking Script
# Author: Casey setup by ChatGPT
# =====================================================

PKG="com.frontieraudio.heartbeat"
DURATION=30                      # seconds
TRACE_FILE="vad_trace.perfetto-trace"
CONFIG_FILE="vad_trace_config.json"

# --- 1. Write Perfetto config to device --------------------------------------
cat > /tmp/$CONFIG_FILE <<'EOF'
{
  "buffers": [{ "size_kb": 8192, "fill_policy": "ring_buffer" }],
  "duration_ms": 30000,
  "data_sources": [
    { "config": { "name": "linux.ftrace",
      "ftrace_config": {
        "ftrace_events": [
          "sched/sched_switch",
          "sched/sched_wakeup",
          "sched/sched_process_exit"
        ]
      }
    }},
    { "config": { "name": "process_stats" } },
    { "config": { "name": "track_event" } },
    { "config": { "name": "android.power" } },
    { "config": { "name": "android.processes" } },
    { "config": { "name": "android.packages_list" } }
  ]
}
EOF

adb push /tmp/$CONFIG_FILE /data/local/tmp/

# --- 2. Get target PID -------------------------------------------------------
PID=$(adb shell pidof $PKG | tr -d '\r')
if [ -z "$PID" ]; then
  echo "❌ Could not find PID for $PKG. Make sure app is running."
  exit 1
fi
echo "✅ PID = $PID"

# --- 3. Run Perfetto trace ---------------------------------------------------
echo "📈 Starting Perfetto trace for $DURATION s..."
adb shell perfetto --txt -c /data/local/tmp/$CONFIG_FILE \
  -o /data/misc/perfetto-traces/$TRACE_FILE >/dev/null 2>&1 &
sleep $DURATION
echo "✅ Perfetto trace complete."

# --- 4. Pull trace -----------------------------------------------------------
adb pull /data/misc/perfetto-traces/$TRACE_FILE .
echo "✅ Trace saved to $(pwd)/$TRACE_FILE"

# --- 5. Run simpleperf sample ------------------------------------------------
echo "🔬 Running simpleperf sampling for 10s..."
adb shell simpleperf record -p $PID --duration 10 --call-graph fp
adb shell simpleperf report > simpleperf_report.txt
adb pull /data/local/tmp/perf.data . 2>/dev/null || true
adb pull simpleperf_report.txt . 2>/dev/null || true
echo "✅ simpleperf results saved."

# --- 6. Optional battery stats snapshot -------------------------------------
echo "🔋 Capturing batterystats..."
adb shell dumpsys batterystats > batterystats_vad.txt
adb pull batterystats_vad.txt .

# --- 7. Cleanup --------------------------------------------------------------
adb shell rm /data/local/tmp/$CONFIG_FILE >/dev/null 2>&1
echo "🧹 Cleanup done."

echo "----------------------------------------------------------"
echo "Open Perfetto trace at: https://ui.perfetto.dev"
echo "File: $(pwd)/$TRACE_FILE"
echo "simpleperf summary: $(pwd)/simpleperf_report.txt"
echo "Battery stats: $(pwd)/batterystats_vad.txt"
echo "----------------------------------------------------------"

