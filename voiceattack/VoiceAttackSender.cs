// Paste this entire file into VoiceAttack's "Inline C# Function" editor.
// VoiceAttack supplies VA. Do not declare a second VA field here.
// References: System.dll;System.Core.dll;Microsoft.CSharp.dll;System.Web.Extensions.dll
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Web.Script.Serialization;

public class VAInline
{
    private static readonly object Gate = new object();
    private const int Port = 42736;
    private const int TimeoutMs = 1500;
    private const int MaximumLineBytes = 8192;
    private const string SharedReceiver = "~~SCVoice.receiver";
    private const string SharedEpoch = "~~SCVoice.epoch";
    private const string SharedFailed = "~~SCVoice.failed";
    private static readonly HashSet<string> DigitalButtons = new HashSet<string>(new[]
        { "A", "B", "X", "Y", "LB", "RB", "BACK", "START", "LS", "RS", "UP", "DOWN", "LEFT", "RIGHT" });

    public void main()
    {
        lock (Gate)
        {
            try
            {
                if (VA.GetBoolean(SharedFailed) == true)
                    throw new InvalidOperationException("This macro has already failed. Start a new voice command.");
                string op = ReadText("~passedText1", null);
                string button = ReadText("~passedText2", null);
                string player = ReadText("~passedText3", "player1");
                int? duration = VA.GetInt("~passedInteger1");
                decimal? value = VA.GetDecimal("~passedDecimal1");
                Validate(op, button, player, duration, value);

                using (TcpClient client = new TcpClient(AddressFamily.InterNetwork))
                {
                    IAsyncResult pending = client.BeginConnect(IPAddress.Loopback, Port, null, null);
                    using (System.Threading.WaitHandle handle = pending.AsyncWaitHandle)
                    {
                        if (!handle.WaitOne(TimeoutMs))
                            throw new IOException("Receiver connection timed out.");
                        client.EndConnect(pending);
                    }
                    client.NoDelay = true;
                    client.ReceiveTimeout = TimeoutMs;
                    client.SendTimeout = TimeoutMs;
                    using (NetworkStream stream = client.GetStream())
                    {
                        JavaScriptSerializer json = new JavaScriptSerializer();
                        Dictionary<string, object> ready = ReadJson(stream, json);
                        RequireVersion(ready);
                        if (Text(ready, "op") != "ready")
                            throw new IOException("Receiver did not send a ready greeting.");
                        string receiver = Text(ready, "receiver");
                        Guid receiverId;
                        if (!Guid.TryParse(receiver, out receiverId))
                            throw new IOException("Invalid receiver identity.");
                        long currentEpoch = Integer(ready, "epoch");
                        if (currentEpoch < 0)
                            throw new IOException("Invalid receiver epoch.");

                        string pinnedReceiver = VA.GetText(SharedReceiver);
                        string pinnedEpochText = VA.GetText(SharedEpoch);
                        long pinnedEpoch;
                        if (pinnedReceiver == null && pinnedEpochText == null)
                        {
                            pinnedReceiver = receiver;
                            pinnedEpoch = currentEpoch;
                            VA.SetText(SharedReceiver, pinnedReceiver, false);
                            VA.SetText(SharedEpoch, pinnedEpoch.ToString(CultureInfo.InvariantCulture), false);
                        }
                        else if (pinnedReceiver == null || !Int64.TryParse(pinnedEpochText,
                            NumberStyles.None, CultureInfo.InvariantCulture, out pinnedEpoch))
                        {
                            throw new IOException("Invalid macro generation state.");
                        }
                        else if (pinnedReceiver != receiver || pinnedEpoch != currentEpoch)
                        {
                            throw new IOException("Receiver was reset or restarted during this macro. Start a new voice command.");
                        }

                        // Local-only macro barrier: greeting pins state; no controller request is sent.
                        if (op == "begin") return;

                        string session = VA.GetText("SCVoice.session");
                        Guid sessionId;
                        long sequence = 0;
                        if (!Guid.TryParse(session, out sessionId))
                        {
                            session = Guid.NewGuid().ToString();
                            VA.SetText("SCVoice.session", session, false);
                        }
                        else
                        {
                            string sequenceText = VA.GetText("SCVoice.sequence");
                            if (!Int64.TryParse(sequenceText, NumberStyles.None,
                                CultureInfo.InvariantCulture, out sequence) || sequence < 0)
                                throw new IOException("Invalid sender sequence state. Reload the profile to reset its runtime variables.");
                        }
                        sequence = checked(sequence + 1);
                        if (sequence > 9007199254740991L)
                            throw new IOException("Sender sequence limit reached. Reload the profile to reset its runtime variables.");
                        VA.SetText("SCVoice.sequence", sequence.ToString(CultureInfo.InvariantCulture), false);
                        Dictionary<string, object> request = new Dictionary<string, object>();
                        request["v"] = 1;
                        request["receiver"] = pinnedReceiver;
                        request["epoch"] = pinnedEpoch;
                        request["session"] = session;
                        request["seq"] = sequence;
                        request["player"] = player;
                        request["op"] = op;
                        if (button != null) request["button"] = button;
                        if (duration.HasValue) request["duration_ms"] = duration.Value;
                        if (value.HasValue) request["value"] = value.Value;
                        byte[] packet = Encoding.UTF8.GetBytes(json.Serialize(request) + "\n");
                        stream.Write(packet, 0, packet.Length);
                        Dictionary<string, object> ack = ReadJson(stream, json);
                        RequireVersion(ack);
                        if (Text(ack, "op") == "error")
                            throw new IOException("Receiver rejected request: " + Text(ack, "message"));
                        if (Text(ack, "op") != "ack" || Integer(ack, "seq") != sequence ||
                            Text(ack, "receiver") != pinnedReceiver)
                            throw new IOException("Invalid receiver acknowledgement.");
                        long acknowledgedEpoch = Integer(ack, "epoch");
                        if ((op == "clear" || op == "release_all") ? acknowledgedEpoch <= pinnedEpoch : acknowledgedEpoch != pinnedEpoch)
                            throw new IOException("Unexpected receiver generation in acknowledgement.");
                        // Keep the chain's original epoch. Cleanup must invalidate later macro steps.
                    }
                }
            }
            catch (Exception error)
            {
                VA.SetBoolean(SharedFailed, true, false);
                VA.WriteToLog("SimpleController voice: " + error.Message +
                    " No automatic retry. If delivery is uncertain, use a new 'release voice controls' command.", "red");
            }
        }
    }

    private string ReadText(string name, string fallback)
    {
        string value = VA.GetText(name);
        if (String.IsNullOrWhiteSpace(value)) return fallback;
        return value.Trim();
    }

    private static void Validate(string op, string button, string player, int? duration, decimal? value)
    {
        if (player != "player1" && player != "player2")
            throw new ArgumentException("Player must be player1 or player2.");
        if (duration.HasValue && (duration.Value < 1 || duration.Value > 600000))
            throw new ArgumentException("Duration must be 1 through 600000 milliseconds, or unset for a persistent hold.");
        if (value.HasValue && (value.Value < 0m || value.Value > 1m))
            throw new ArgumentException("Trigger value must be 0 through 1.");
        bool digital = button != null && DigitalButtons.Contains(button);
        bool trigger = button == "LT" || button == "RT";
        if (op == "tap" || op == "hold")
        {
            if (!digital || value.HasValue) throw new ArgumentException("Tap/hold requires a digital button and no trigger value.");
        }
        else if (op == "release")
        {
            if ((!digital && !trigger) || duration.HasValue || value.HasValue)
                throw new ArgumentException("Release requires a button or trigger, with duration and value unset.");
        }
        else if (op == "trigger")
        {
            if (!trigger || !value.HasValue) throw new ArgumentException("Trigger requires LT/RT and a decimal value.");
        }
        else if (op == "begin" || op == "clear" || op == "release_all")
        {
            if (button != null || duration.HasValue || value.HasValue)
                throw new ArgumentException("Cleanup requires button, duration and value unset.");
        }
        else throw new ArgumentException("Action must be begin, tap, hold, release, trigger, clear or release_all.");
    }

    private static Dictionary<string, object> ReadJson(NetworkStream stream, JavaScriptSerializer json)
    {
        using (MemoryStream line = new MemoryStream())
        {
            while (line.Length < MaximumLineBytes)
            {
                int next = stream.ReadByte();
                if (next == -1) throw new IOException("Receiver closed the connection before acknowledgement.");
                if (next == 10)
                {
                    string text = new UTF8Encoding(false, true).GetString(line.ToArray());
                    Dictionary<string, object> result = json.DeserializeObject(text) as Dictionary<string, object>;
                    if (result == null) throw new IOException("Receiver returned invalid JSON.");
                    return result;
                }
                line.WriteByte((byte)next);
            }
        }
        throw new IOException("Receiver response exceeded the size limit.");
    }

    private static void RequireVersion(Dictionary<string, object> data)
    {
        if (Integer(data, "v") != 1) throw new IOException("Unsupported receiver protocol version.");
    }

    private static string Text(Dictionary<string, object> data, string key)
    {
        object value;
        if (!data.TryGetValue(key, out value) || !(value is string))
            throw new IOException("Receiver response is missing text field " + key + ".");
        return (string)value;
    }

    private static long Integer(Dictionary<string, object> data, string key)
    {
        object value;
        if (!data.TryGetValue(key, out value) || (!(value is int) && !(value is long)))
            throw new IOException("Receiver response is missing integer field " + key + ".");
        return Convert.ToInt64(value, CultureInfo.InvariantCulture);
    }
}
