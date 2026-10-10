// Isolated proxy + TCP mock. This does not load VoiceAttack or receiver code.
using System.Threading;

public class SenderMockProxy
{
    public static Dictionary<string, object> Global = new Dictionary<string, object>();
    public Dictionary<string, object> Chain = new Dictionary<string, object>();
    public Dictionary<string, object> Parameters = new Dictionary<string, object>();
    public List<string> Errors = new List<string>();
    private object Get(string name)
    {
        object value;
        Dictionary<string, object> target = name.StartsWith("~~") ? Chain :
            name.StartsWith("~") ? Parameters : Global;
        return target.TryGetValue(name, out value) ? value : null;
    }
    private void Set(string name, object value)
    {
        Dictionary<string, object> target = name.StartsWith("~~") ? Chain : Global;
        target[name] = value;
    }
    public string GetText(string name) { return (string)Get(name); }
    public int? GetInt(string name) { return (int?)Get(name); }
    public decimal? GetDecimal(string name) { return (decimal?)Get(name); }
    public bool? GetBoolean(string name) { return (bool?)Get(name); }
    public void SetText(string name, string value, bool save) { if (save) throw new Exception("Unexpected profile write"); Set(name, value); }
    public void SetBoolean(string name, bool? value, bool save) { if (save) throw new Exception("Unexpected profile write"); Set(name, value); }
    public void WriteToLog(string text, string color) { Errors.Add(text); }
}

public static class SenderMockServer
{
    public static int Port;
    public static long Epoch;
    public static string Receiver = Guid.NewGuid().ToString();
    public static List<Dictionary<string, object>> Requests = new List<Dictionary<string, object>>();
    public static string NextReply;
    private static TcpListener Listener;
    private static Thread Worker;
    public static void Start()
    {
        Listener = new TcpListener(IPAddress.Loopback, 0);
        Listener.Start();
        Port = ((IPEndPoint)Listener.LocalEndpoint).Port;
        Worker = new Thread(Serve);
        Worker.IsBackground = true;
        Worker.Start();
    }
    private static void Serve()
    {
        JavaScriptSerializer json = new JavaScriptSerializer();
        while (true)
        {
            try
            {
                using (TcpClient client = Listener.AcceptTcpClient())
                using (NetworkStream stream = client.GetStream())
                {
                    stream.ReadTimeout = 3000;
                    Write(stream, json.Serialize(new { v = 1, op = "ready", receiver = Receiver, epoch = Epoch }));
                    using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, false, 1024, true))
                    {
                        string line = reader.ReadLine();
                        if (line == null) continue; // Stale-chain check intentionally closes without sending.
                        Dictionary<string, object> request = json.DeserializeObject(line) as Dictionary<string, object>;
                        lock (Requests) Requests.Add(request);
                        string next = NextReply;
                        NextReply = null;
                        if (next == "drop") continue;
                        if (next == "error")
                        {
                            Write(stream, json.Serialize(new { v = 1, op = "error", message = "Mock rejection", receiver = Receiver, epoch = Epoch }));
                            continue;
                        }
                        if ((string)request["op"] == "clear" || (string)request["op"] == "release_all") Epoch++;
                        if (next == "oversize") { Write(stream, new string('x', 8193)); continue; }
                        long seq = Convert.ToInt64(request["seq"]);
                        Write(stream, json.Serialize(new { v = 1, op = "ack", receiver = Receiver,
                            epoch = Epoch, seq = next == "wrong_seq" ? seq + 1 : seq }));
                    }
                }
            }
            catch (IOException) { }
            catch (SocketException) { return; }
        }
    }
    private static void Write(NetworkStream stream, string text)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text + "\n");
        stream.Write(bytes, 0, bytes.Length);
    }
    public static void Stop() { Listener.Stop(); Worker.Join(1000); }
}

public static class SenderMockHarness
{
    private static int Checks;
    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception("FAIL: " + message);
        Checks++;
    }
    private static SenderMockProxy Call(string op, string button, int? duration, decimal? value, string player, SenderMockProxy proxy)
    {
        if (proxy == null) proxy = new SenderMockProxy();
        proxy.Parameters.Clear();
        if (op != null) proxy.Parameters["~passedText1"] = op;
        if (button != null) proxy.Parameters["~passedText2"] = button;
        if (duration.HasValue) proxy.Parameters["~passedInteger1"] = duration.Value;
        if (value.HasValue) proxy.Parameters["~passedDecimal1"] = value.Value;
        if (player != null) proxy.Parameters["~passedText3"] = player;
        VAInline inline = new VAInline();
        inline.VA = proxy;
        inline.main();
        return proxy;
    }
    public static int Main()
    {
        SenderMockServer.Start();
        try
        {
            SenderMockProxy notStarted = Call("begin", null, null, null, null, null);
            Check(notStarted.Errors.Count == 0 && SenderMockServer.Requests.Count == 0, "Begin sends no controller action");
            Call("clear", null, null, null, null, null);
            int beginCount = SenderMockServer.Requests.Count;
            Call("tap", "A", 100, null, null, notStarted);
            Check(notStarted.Errors.Count == 1 && SenderMockServer.Requests.Count == beginCount, "Reset during initial pause blocks first action");
            SenderMockServer.Requests.Clear();
            SenderMockProxy chain = Call("tap", "A", 100, null, null, null);
            Check(chain.Errors.Count == 0, "Tap acknowledged");
            Check((int)SenderMockServer.Requests[0]["duration_ms"] == 100, "Tap duration transmitted");
            Call("hold", "RB", null, null, "player2", chain);
            Check(!SenderMockServer.Requests[1].ContainsKey("duration_ms"), "Persistent hold has no timer");
            Check((string)SenderMockServer.Requests[1]["player"] == "player2", "Player 2 transmitted");
            Call("hold", "B", 500, null, null, chain);
            Check((int)SenderMockServer.Requests[2]["duration_ms"] == 500, "Finite hold duration transmitted");
            Call("trigger", "RT", 750, 0.8m, null, chain);
            Check(Convert.ToDecimal(SenderMockServer.Requests[3]["value"]) == 0.8m, "Trigger value transmitted");
            Call("release", "RT", null, null, null, chain);
            Check((string)SenderMockServer.Requests[4]["op"] == "release", "Trigger release transmitted");
            Call("clear", null, null, null, null, null);
            int before = SenderMockServer.Requests.Count;
            Call("tap", "X", 100, null, null, chain);
            Check(SenderMockServer.Requests.Count == before && chain.Errors.Count == 1, "Old macro blocked after separate clear");
            SenderMockProxy fresh = Call("tap", "Y", null, null, null, null);
            Check(fresh.Errors.Count == 0, "New macro works after clear");
            Check(!SenderMockServer.Requests[before].ContainsKey("duration_ms"), "Default tap duration omitted for receiver default");
            Call("release_all", null, null, null, null, fresh);
            before = SenderMockServer.Requests.Count;
            Call("hold", "A", null, null, null, fresh);
            Check(SenderMockServer.Requests.Count == before && fresh.Errors.Count == 1, "Later steps blocked after own global reset");
            SenderMockServer.NextReply = "drop";
            SenderMockProxy uncertain = Call("tap", "A", 100, null, null, null);
            before = SenderMockServer.Requests.Count;
            Call("tap", "B", 100, null, null, uncertain);
            Check(uncertain.Errors.Count == 2 && SenderMockServer.Requests.Count == before, "No replay or continuation after lost acknowledgement");
            foreach (string reply in new[] { "error", "wrong_seq", "oversize" })
            {
                SenderMockServer.NextReply = reply;
                SenderMockProxy bad = Call("tap", "A", 100, null, null, null);
                Check(bad.Errors.Count == 1, "Reject " + reply + " response");
            }
            before = SenderMockServer.Requests.Count;
            SenderMockProxy invalid = Call("trigger", "RT", null, 1.1m, null, null);
            Check(invalid.Errors.Count == 1 && SenderMockServer.Requests.Count == before, "Invalid trigger rejected before network");
            SenderMockProxy invalidButton = Call("tap", "A,B", 100, null, null, null);
            Check(invalidButton.Errors.Count == 1 && SenderMockServer.Requests.Count == before, "Compound button rejected before network");
            SenderMockProxy reset = Call("tap", "A", 100, null, null, null);
            SenderMockServer.Receiver = Guid.NewGuid().ToString();
            before = SenderMockServer.Requests.Count;
            Call("tap", "B", 100, null, null, reset);
            Check(reset.Errors.Count == 1 && SenderMockServer.Requests.Count == before, "Restart invalidates old macro");
            Check(Call("release", "A", null, null, null, null).Errors.Count == 0, "New chain recovers after restart");
            long previous = 0;
            string session = null;
            foreach (Dictionary<string, object> request in SenderMockServer.Requests)
            {
                long sequence = Convert.ToInt64(request["seq"]);
                Check(sequence > previous, "Monotonic sender sequences");
                previous = sequence;
                if (session == null) session = (string)request["session"];
                Check((string)request["session"] == session, "Stable sender session");
            }
            Console.WriteLine("PASS: " + Checks + " mock assertions; tap, finite/persistent hold, triggers, Player 2, clear/reset barriers, restart, rejected/missing ACK, invalid arguments. Only mock loopback port " + SenderMockServer.Port + ".");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { SenderMockServer.Stop(); }
    }
}
