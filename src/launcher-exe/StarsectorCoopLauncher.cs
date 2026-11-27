using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("Starsector Coop Launcher")]
[assembly: AssemblyDescription("One-click installer, updater and launcher for Starsector Coop")]
[assembly: AssemblyProduct("Starsector Coop")]
[assembly: AssemblyVersion("0.2.14.0")]
[assembly: AssemblyFileVersion("0.2.14.0")]

namespace StarsectorCoopBootstrap
{
    internal static class Program
    {
        internal const string Repo = "openmans/starsector-coop";
        internal const string LatestReleaseApi = "https://api.github.com/repos/" + Repo + "/releases/latest";
        internal const string ReleasesListApi = "https://api.github.com/repos/" + Repo + "/releases?per_page=5";
        internal const string WingmanRepo = "NEOysod/TheWingmanProject";
        internal const string WingmanLatestApi = "https://api.github.com/repos/" + WingmanRepo + "/releases/latest";
        internal const string WingmanListApi = "https://api.github.com/repos/" + WingmanRepo + "/releases?per_page=5";
        internal const string RussianLatestApi = "https://api.github.com/repos/mrsokol2552-source/starsector-ru-strategic/releases/latest";
        internal const string RussianDirectUrl = "https://github.com/mrsokol2552-source/starsector-ru-strategic/releases/latest/download/Starsector-RU-RC9-Windows-x64.zip";
        internal const string ForksEntry = "..\\mods\\coop\\jars\\coop-forks.jar;";

        internal static readonly string[] CanonicalStockJars = new[]
        {
            "janino.jar", "commons-compiler.jar", "commons-compiler-jdk.jar",
            "starfarer.api.jar", "starfarer_obf.jar", "jogg-0.0.7.jar", "jorbis-0.0.15.jar",
            "json.jar", "jinput.jar", "log4j-1.2.9.jar", "lwjgl.jar", "lwjgl_util.jar",
            "fs.sound_obf.jar", "fs.common_obf.jar", "xstream-1.4.10.jar"
        };

        internal static readonly string[] CommonInstallDirs = new[]
        {
            @"C:\Program Files (x86)\Fractal Softworks\Starsector",
            @"C:\Program Files\Fractal Softworks\Starsector",
            @"C:\Starsector",
            @"D:\Starsector",
            @"D:\Games\Starsector",
            @"E:\Starsector",
            @"G:\Starsector"
        };

        [STAThread]
        internal static int Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | SecurityProtocolType.Tls12;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool forceSetup = false;
            bool noLaunch = false;
            bool withWingman = false;
            string explicitTarget = null;

            for (int i = 0; i < args.Length; i++)
            {
                string arg = (args[i] ?? "").Trim();
                if (arg.Equals("--setup", StringComparison.OrdinalIgnoreCase))
                {
                    forceSetup = true;
                }
                else if (arg.Equals("--no-launch", StringComparison.OrdinalIgnoreCase))
                {
                    noLaunch = true;
                }
                else if (arg.Equals("--with-wingman", StringComparison.OrdinalIgnoreCase))
                {
                    withWingman = true;
                }
                else if (arg.Equals("--install", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
                {
                    explicitTarget = args[++i];
                }
                else if (!arg.StartsWith("-") && explicitTarget == null)
                {
                    explicitTarget = arg;
                }
            }

            // Headless / elevated install mode when --install <path> is supplied.
            if (!string.IsNullOrWhiteSpace(explicitTarget))
            {
                string resolved = ResolveInstallRoot(explicitTarget);
                if (resolved == null)
                {
                    if (!noLaunch)
                    {
                        MessageBox.Show(
                            "Not a valid Starsector folder (starsector.exe, vmparams and starsector-core required):\n\n" + explicitTarget,
                            "Starsector Coop Launcher", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }
                    return 1;
                }
                if (noLaunch)
                {
                    try
                    {
                        InstallAndConfigure(resolved, withWingman, null);
                        return 0;
                    }
                    catch (Exception ex)
                    {
                        Console.Error.WriteLine(ex.Message);
                        return 1;
                    }
                }
                using (var form = new InstallerForm(resolved, true, withWingman))
                {
                    Application.Run(form);
                    return form.ExitCode;
                }
            }

            // Fast path: if already installed and ready, apply pending jars and launch immediately.
            if (!forceSetup)
            {
                string readyRoot = FindReadyInstall();
                if (readyRoot != null)
                {
                    ApplyPendingJars(GetModRoot(readyRoot));
                    if (LaunchJavaLauncher(readyRoot))
                    {
                        return 0;
                    }
                }
            }

            string initialRoot = FindAnyStarsectorInstall();
            using (var form = new InstallerForm(initialRoot, false, withWingman))
            {
                Application.Run(form);
                return form.ExitCode;
            }
        }

        internal static string SelfDir()
        {
            return Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location) ?? Environment.CurrentDirectory;
        }

        internal static string SelfExe()
        {
            return Assembly.GetExecutingAssembly().Location;
        }

        internal static string SavedInstallPathFile()
        {
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            return Path.Combine(Path.Combine(appData, "StarsectorCoop"), "install-path.txt");
        }

        internal static void SaveInstallRoot(string root)
        {
            try
            {
                string file = SavedInstallPathFile();
                Directory.CreateDirectory(Path.GetDirectoryName(file));
                File.WriteAllText(file, root, Encoding.UTF8);
            }
            catch { }
        }

        internal static string LoadSavedInstallRoot()
        {
            try
            {
                string file = SavedInstallPathFile();
                if (File.Exists(file))
                {
                    string candidate = File.ReadAllText(file, Encoding.UTF8).Trim();
                    return ResolveInstallRoot(candidate);
                }
            }
            catch { }
            return null;
        }

        internal static bool LooksLikeInstall(string dir)
        {
            if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) return false;
            return File.Exists(Path.Combine(dir, "starsector.exe"))
                && File.Exists(Path.Combine(dir, "vmparams"))
                && Directory.Exists(Path.Combine(dir, "starsector-core"));
        }

        internal static string ResolveInstallRoot(string selected)
        {
            if (string.IsNullOrWhiteSpace(selected)) return null;
            selected = selected.Trim().Trim('"');
            if (File.Exists(selected))
            {
                string parent = Path.GetDirectoryName(selected);
                return LooksLikeInstall(parent) ? parent : null;
            }
            if (LooksLikeInstall(selected)) return selected;
            string leaf = Path.GetFileName(selected.TrimEnd('\\', '/'));
            if (leaf.Equals("starsector-core", StringComparison.OrdinalIgnoreCase)
                || leaf.Equals("mods", StringComparison.OrdinalIgnoreCase))
            {
                string parent = Path.GetDirectoryName(selected.TrimEnd('\\', '/'));
                if (LooksLikeInstall(parent)) return parent;
            }
            return null;
        }

        internal static string GetModRoot(string installRoot)
        {
            return Path.Combine(Path.Combine(installRoot, "mods"), "coop");
        }

        internal static bool IsModReady(string installRoot)
        {
            if (!LooksLikeInstall(installRoot)) return false;
            string mod = GetModRoot(installRoot);
            string jars = Path.Combine(mod, "jars");
            return File.Exists(Path.Combine(mod, "mod_info.json"))
                && File.Exists(Path.Combine(jars, "coop-launcher.jar"))
                && File.Exists(Path.Combine(jars, "flatlaf.jar"))
                && File.Exists(Path.Combine(jars, "coop.jar"))
                && File.Exists(Path.Combine(jars, "coop-forks.jar"));
        }

        internal static string FindReadyInstall()
        {
            // 1. User's explicitly remembered Starsector install takes priority over auto-discovery
            string saved = LoadSavedInstallRoot();
            if (saved != null && IsModReady(saved)) return saved;

            // 2. Sitting inside <Starsector>\mods\coop
            try
            {
                string twoUp = Path.GetFullPath(Path.Combine(SelfDir(), "..\\.."));
                if (IsModReady(twoUp)) return twoUp;
            }
            catch { }

            // 3. Common Starsector directories
            foreach (string dir in CommonInstallDirs)
            {
                if (IsModReady(dir)) return dir;
            }
            return null;
        }

        internal static string FindAnyStarsectorInstall()
        {
            string saved = LoadSavedInstallRoot();
            if (saved != null) return saved;

            try
            {
                string twoUp = Path.GetFullPath(Path.Combine(SelfDir(), "..\\.."));
                if (LooksLikeInstall(twoUp)) return twoUp;
            }
            catch { }

            foreach (string dir in CommonInstallDirs)
            {
                if (LooksLikeInstall(dir)) return dir;
            }
            return null;
        }

        internal static bool TestDirWritable(string dir)
        {
            string probe = Path.Combine(dir, ".coop-write-probe-" + Guid.NewGuid().ToString("N") + ".tmp");
            try
            {
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return true;
            }
            catch
            {
                return false;
            }
        }

        internal static bool IsAdministrator()
        {
            using (WindowsIdentity id = WindowsIdentity.GetCurrent())
            {
                return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
            }
        }

        internal static void ApplyPendingJars(string modRoot)
        {
            string jarsDir = Path.Combine(modRoot, "jars");
            if (!Directory.Exists(jarsDir)) return;

            for (int attempt = 0; attempt < 6; attempt++)
            {
                string[] pending = Directory.GetFiles(jarsDir, "*.jar.pending");
                if (pending.Length == 0) return;

                bool allDone = true;
                foreach (string src in pending)
                {
                    string dst = src.Substring(0, src.Length - ".pending".Length);
                    try
                    {
                        if (File.Exists(dst)) File.Delete(dst);
                        File.Move(src, dst);
                    }
                    catch
                    {
                        allDone = false;
                    }
                }
                if (allDone) return;
                Thread.Sleep(500);
            }
        }

        internal static bool LaunchJavaLauncher(string installRoot)
        {
            string modRoot = GetModRoot(installRoot);
            string jars = Path.Combine(modRoot, "jars");
            string core = Path.Combine(installRoot, "starsector-core");
            string javaw = Path.Combine(Path.Combine(Path.Combine(installRoot, "jre"), "bin"), "javaw.exe");

            if (!File.Exists(javaw))
            {
                javaw = "javaw.exe";
            }

            string cp = string.Join(";", new[]
            {
                Path.Combine(jars, "coop-launcher.jar"),
                Path.Combine(jars, "flatlaf.jar"),
                Path.Combine(jars, "coop.jar"),
                Path.Combine(core, "json.jar"),
                Path.Combine(core, "log4j-1.2.9.jar")
            });

            var psi = new ProcessStartInfo
            {
                FileName = javaw,
                Arguments = "-cp \"" + cp + "\" coop.launcher.CoopLauncherApp",
                WorkingDirectory = modRoot,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            Process.Start(psi);
            SaveInstallRoot(installRoot);
            return true;
        }

        internal sealed class ReleaseAsset
        {
            public string Version;
            public string AssetName;
            public string DownloadUrl;
        }

        internal static ReleaseAsset ResolveLatestAsset()
        {
            var serializer = new JavaScriptSerializer();
            Dictionary<string, object> release = TryFetchReleaseObject(LatestReleaseApi, serializer);
            if (release == null)
            {
                string listJson = FetchText(ReleasesListApi);
                var list = serializer.DeserializeObject(listJson) as IEnumerable;
                if (list != null)
                {
                    foreach (object item in list)
                    {
                        var dict = item as IDictionary<string, object>;
                        if (dict == null) continue;
                        bool draft = dict.ContainsKey("draft") && Convert.ToBoolean(dict["draft"]);
                        if (!draft && FindZipAsset(dict) != null)
                        {
                            release = new Dictionary<string, object>(dict);
                            break;
                        }
                    }
                }
            }
            if (release == null)
            {
                throw new InvalidOperationException("No downloadable release found on GitHub (" + Repo + ").");
            }
            ReleaseAsset asset = FindZipAsset(release);
            if (asset == null)
            {
                throw new InvalidOperationException("Latest GitHub release has no .zip archive.");
            }
            return asset;
        }

        private static Dictionary<string, object> TryFetchReleaseObject(string url, JavaScriptSerializer serializer)
        {
            try
            {
                string json = FetchText(url);
                return serializer.Deserialize<Dictionary<string, object>>(json);
            }
            catch (WebException ex)
            {
                var resp = ex.Response as HttpWebResponse;
                if (resp != null && resp.StatusCode == HttpStatusCode.NotFound)
                {
                    return null;
                }
                throw;
            }
        }

        private static string FetchText(string url)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method = "GET";
            req.Timeout = 15000;
            req.ReadWriteTimeout = 15000;
            req.UserAgent = "starsector-coop-launcher-exe";
            req.Accept = "application/vnd.github+json";
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var stream = resp.GetResponseStream())
            using (var reader = new StreamReader(stream, Encoding.UTF8))
            {
                return reader.ReadToEnd();
            }
        }

        private static ReleaseAsset FindZipAsset(IDictionary<string, object> release)
        {
            string tag = release.ContainsKey("tag_name") ? Convert.ToString(release["tag_name"]) : "";
            string version = Regex.Replace(tag.Trim(), "^[vV]", "");
            object assetsObj;
            if (!release.TryGetValue("assets", out assetsObj)) return null;
            var assets = assetsObj as IEnumerable;
            if (assets == null) return null;

            ReleaseAsset fallback = null;
            foreach (object obj in assets)
            {
                var a = obj as IDictionary<string, object>;
                if (a == null) continue;
                string name = a.ContainsKey("name") ? Convert.ToString(a["name"]).Trim() : "";
                string dl = a.ContainsKey("browser_download_url") ? Convert.ToString(a["browser_download_url"]).Trim() : "";
                if (string.IsNullOrEmpty(dl) || !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) continue;
                var candidate = new ReleaseAsset { Version = version, AssetName = name, DownloadUrl = dl };
                if (name.StartsWith("coop-", StringComparison.OrdinalIgnoreCase)) return candidate;
                if (fallback == null) fallback = candidate;
            }
            return fallback;
        }

        internal static ReleaseAsset ResolveLatestWingmanAsset()
        {
            var serializer = new JavaScriptSerializer();
            Dictionary<string, object> release = TryFetchReleaseObject(WingmanLatestApi, serializer);
            if (release != null)
            {
                ReleaseAsset direct = FindWingmanZipAsset(release);
                if (direct != null) return direct;
            }
            string listJson = FetchText(WingmanListApi);
            var list = serializer.DeserializeObject(listJson) as IEnumerable;
            if (list != null)
            {
                foreach (object item in list)
                {
                    var dict = item as IDictionary<string, object>;
                    if (dict == null) continue;
                    bool draft = dict.ContainsKey("draft") && Convert.ToBoolean(dict["draft"]);
                    if (draft) continue;
                    ReleaseAsset found = FindWingmanZipAsset(dict);
                    if (found != null) return found;
                }
            }
            throw new InvalidOperationException("No downloadable Wingman-*.zip found on GitHub (" + WingmanRepo + ").");
        }

        private static ReleaseAsset FindWingmanZipAsset(IDictionary<string, object> release)
        {
            string tag = release.ContainsKey("tag_name") ? Convert.ToString(release["tag_name"]) : "";
            string version = Regex.Replace(tag.Trim(), "^[vV]", "");
            object assetsObj;
            if (!release.TryGetValue("assets", out assetsObj)) return null;
            var assets = assetsObj as IEnumerable;
            if (assets == null) return null;

            foreach (object obj in assets)
            {
                var a = obj as IDictionary<string, object>;
                if (a == null) continue;
                string name = a.ContainsKey("name") ? Convert.ToString(a["name"]).Trim() : "";
                string dl = a.ContainsKey("browser_download_url") ? Convert.ToString(a["browser_download_url"]).Trim() : "";
                if (string.IsNullOrEmpty(dl) || !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) continue;
                if (name.StartsWith("Wingman-", StringComparison.OrdinalIgnoreCase)
                    && !name.StartsWith("WingmanPvP", StringComparison.OrdinalIgnoreCase))
                {
                    return new ReleaseAsset { Version = version, AssetName = name, DownloadUrl = dl };
                }
            }
            return null;
        }

        internal static string InstallAndConfigure(string installRoot, bool includeWingman, Action<string, int> report)
        {
            Action<string, int> log = report ?? ((s, p) => { });
            log("Checking GitHub (" + Repo + ")...", 5);
            ReleaseAsset asset = ResolveLatestAsset();
            log("Downloading " + asset.AssetName + " (v" + asset.Version + ")...", 10);

            string tempZip = Path.Combine(Path.GetTempPath(), "starsector-coop-" + Guid.NewGuid().ToString("N") + ".zip");
            try
            {
                DownloadFile(asset.DownloadUrl, tempZip, pct => log("Downloading " + asset.AssetName + "... " + pct + "%", 10 + (pct * 55 / 100)));

                string modRoot = GetModRoot(installRoot);
                log("Extracting mod files to mods\\coop...", 68);
                int count = ExtractModZip(tempZip, modRoot, "coop");

                // Copy this .exe into mods\coop too so the player has it right inside the mod folder.
                try
                {
                    string targetExe = Path.Combine(modRoot, "Starsector Coop Launcher.exe");
                    if (!string.Equals(Path.GetFullPath(SelfExe()), Path.GetFullPath(targetExe), StringComparison.OrdinalIgnoreCase))
                    {
                        File.Copy(SelfExe(), targetExe, true);
                    }
                }
                catch { }

                log("Patching vmparams (with backup)...", 74);
                PatchVmparams(Path.Combine(installRoot, "vmparams"));

                log("Enabling coop in enabled_mods.json...", 78);
                string enabledPath = Path.Combine(Path.Combine(installRoot, "mods"), "enabled_mods.json");
                EnableMod(enabledPath, "coop");

                string wingmanSuffix = "";
                if (includeWingman)
                {
                    log("Checking Wingman Project on GitHub...", 82);
                    ReleaseAsset wm = ResolveLatestWingmanAsset();
                    string wmZip = Path.Combine(Path.GetTempPath(), "starsector-wingman-" + Guid.NewGuid().ToString("N") + ".zip");
                    try
                    {
                        DownloadFile(wm.DownloadUrl, wmZip, pct => log("Downloading " + wm.AssetName + "... " + pct + "%", 82 + (pct * 12 / 100)));
                        string wmRoot = Path.Combine(Path.Combine(installRoot, "mods"), "Wingman");
                        log("Extracting Wingman to mods\\Wingman...", 95);
                        count += ExtractModZip(wmZip, wmRoot, "Wingman");
                        EnableMod(enabledPath, "wingman_project");
                        wingmanSuffix = " + Wingman v" + wm.Version;
                    }
                    finally
                    {
                        try { if (File.Exists(wmZip)) File.Delete(wmZip); } catch { }
                    }
                }

                SaveInstallRoot(installRoot);
                log("Installed Starsector Coop v" + asset.Version + wingmanSuffix + " (" + count + " files).", 100);
                return asset.Version + wingmanSuffix;
            }
            finally
            {
                try { if (File.Exists(tempZip)) File.Delete(tempZip); } catch { }
            }
        }

        internal static int ExtractModZip(string zipPath, string modRoot, string leadingPrefix)
        {
            Directory.CreateDirectory(modRoot);
            string normRoot = Path.GetFullPath(modRoot).TrimEnd('\\') + "\\";
            string prefixSlash = leadingPrefix + "/";
            int count = 0;
            using (ZipArchive archive = ZipFile.OpenRead(zipPath))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    if (string.IsNullOrEmpty(entry.Name)) continue;
                    string rel = entry.FullName.Replace('\\', '/').TrimStart('/');
                    if (rel.StartsWith(prefixSlash, StringComparison.OrdinalIgnoreCase))
                    {
                        rel = rel.Substring(prefixSlash.Length);
                    }
                    if (string.IsNullOrEmpty(rel)) continue;

                    string dest = Path.GetFullPath(Path.Combine(modRoot, rel.Replace('/', '\\')));
                    if (!dest.StartsWith(normRoot, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new IOException("Unsafe archive entry: " + entry.FullName);
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(dest));
                    try
                    {
                        entry.ExtractToFile(dest, true);
                    }
                    catch (IOException)
                    {
                        if (rel.StartsWith("jars/", StringComparison.OrdinalIgnoreCase)
                            && rel.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
                        {
                            entry.ExtractToFile(dest + ".pending", true);
                        }
                        else
                        {
                            throw;
                        }
                    }
                    count++;
                }
            }
            if (count == 0) throw new IOException("Archive contained no mod files.");
            return count;
        }

        internal static void InstallRussianTranslation(string installRoot, Action<string, int> onProgress)
        {
            string url = RussianDirectUrl;
            try
            {
                var serializer = new JavaScriptSerializer();
                Dictionary<string, object> rel = TryFetchReleaseObject(RussianLatestApi, serializer);
                if (rel != null)
                {
                    ReleaseAsset found = FindZipAsset(rel);
                    if (found != null && !string.IsNullOrEmpty(found.DownloadUrl))
                    {
                        url = found.DownloadUrl;
                    }
                }
            }
            catch { }

            string tempZip = Path.Combine(Path.GetTempPath(), "starsector-ru-" + Guid.NewGuid().ToString("N") + ".zip");
            string tempDir = Path.Combine(Path.GetTempPath(), "starsector-ru-ext-" + Guid.NewGuid().ToString("N"));
            try
            {
                if (onProgress != null) onProgress("Downloading Starsector 0.98.5a-RC9 Russian translation...", 0);
                DownloadFile(url, tempZip, pct =>
                {
                    if (onProgress != null) onProgress("Downloading Russian translation... " + pct + "%", pct);
                });
                if (onProgress != null) onProgress("Extracting Russian translation installer...", 85);
                Directory.CreateDirectory(tempDir);
                ZipFile.ExtractToDirectory(tempZip, tempDir);
                string[] exes = Directory.GetFiles(tempDir, "Starsector-RU.exe", SearchOption.AllDirectories);
                if (exes.Length > 0)
                {
                    if (onProgress != null) onProgress("Applying Starsector 0.98.5a-RC9 Russian translation...", 90);
                    var psi = new ProcessStartInfo
                    {
                        FileName = exes[0],
                        Arguments = "install --game \"" + installRoot + "\" --vanilla-only",
                        WorkingDirectory = Path.GetDirectoryName(exes[0]),
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        CreateNoWindow = true
                    };
                    using (var proc = Process.Start(psi))
                    {
                        string stdout = proc.StandardOutput.ReadToEnd();
                        string stderr = proc.StandardError.ReadToEnd();
                        proc.WaitForExit();
                        if (proc.ExitCode != 0)
                        {
                            throw new InvalidOperationException("Russian installer failed (exit " + proc.ExitCode + "): "
                                + (string.IsNullOrWhiteSpace(stderr) ? stdout : stderr).Trim());
                        }
                    }
                }
                else
                {
                    ExtractModZip(tempZip, Path.Combine(installRoot, "starsector-core"), "starsector-core");
                }
                if (onProgress != null) onProgress("Russian translation installed.", 100);
            }
            finally
            {
                try { if (File.Exists(tempZip)) File.Delete(tempZip); } catch { }
                try { if (Directory.Exists(tempDir)) Directory.Delete(tempDir, true); } catch { }
            }
        }

        private static void DownloadFile(string url, string destination, Action<int> onPercent)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method = "GET";
            req.Timeout = 15000;
            req.ReadWriteTimeout = 30000;
            req.AllowAutoRedirect = true;
            req.UserAgent = "starsector-coop-launcher-exe";
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var input = resp.GetResponseStream())
            using (var output = File.Create(destination))
            {
                long total = resp.ContentLength;
                byte[] buffer = new byte[16384];
                long copied = 0;
                int lastPct = -1;
                int read;
                while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    output.Write(buffer, 0, read);
                    copied += read;
                    if (total > 0)
                    {
                        int pct = (int)Math.Min(100, (copied * 100L) / total);
                        if (pct != lastPct)
                        {
                            lastPct = pct;
                            if (onPercent != null) onPercent(pct);
                        }
                    }
                }
            }
        }

        internal static void PatchVmparams(string vmparamsPath)
        {
            Encoding iso = Encoding.GetEncoding("ISO-8859-1");
            string raw = File.ReadAllText(vmparamsPath, iso);
            string body = raw.TrimEnd('\r', '\n');
            if (body.IndexOf('\n') >= 0 || body.IndexOf('\r') >= 0)
            {
                throw new InvalidOperationException("vmparams has multiple lines; please inspect it manually.");
            }
            const string marker = " -classpath ";
            int idx = body.IndexOf(marker, StringComparison.Ordinal);
            if (idx < 0)
            {
                throw new InvalidOperationException("vmparams has no \" -classpath \" section.");
            }

            string head = body.Substring(0, idx + marker.Length);
            string tail = body.Substring(idx + marker.Length);
            int firstSpace = tail.IndexOf(' ');
            string cpSegment = firstSpace >= 0 ? tail.Substring(0, firstSpace) : tail;
            string mainClass = "com.fs.starfarer.StarfarerLauncher";
            int lastSpace = tail.LastIndexOf(' ');
            if (lastSpace >= 0 && lastSpace + 1 < tail.Length)
            {
                string mc = tail.Substring(lastSpace + 1).Trim();
                if (Regex.IsMatch(mc, "^[A-Za-z0-9_.]+$")) mainClass = mc;
            }

            bool needsRepair = tail.IndexOf('"') >= 0
                || Regex.IsMatch(cpSegment, @"[A-Za-z]:\\")
                || tail.IndexOf("janino.jar", StringComparison.OrdinalIgnoreCase) < 0;

            string patched;
            if (needsRepair)
            {
                patched = head + ForksEntry + string.Join(";", CanonicalStockJars) + " " + mainClass;
            }
            else
            {
                var kept = new List<string>();
                string[] parts = tail.Split(';');
                for (int i = 0; i < parts.Length; i++)
                {
                    if (i < parts.Length - 1 && parts[i].EndsWith("coop-forks.jar", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    kept.Add(parts[i]);
                }
                patched = head + ForksEntry + string.Join(";", kept);
            }

            if (string.Equals(patched, body, StringComparison.Ordinal)) return;

            string backup = vmparamsPath + ".backup";
            if (!File.Exists(backup))
            {
                File.Copy(vmparamsPath, backup, false);
            }
            File.WriteAllText(vmparamsPath, patched, iso);
        }

        internal static void EnableMod(string enabledModsPath, string modId)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(enabledModsPath));
            var mods = new List<string>();
            if (File.Exists(enabledModsPath))
            {
                string existing = File.ReadAllText(enabledModsPath, Encoding.UTF8);
                if (!string.IsNullOrWhiteSpace(existing))
                {
                    var serializer = new JavaScriptSerializer();
                    var doc = serializer.Deserialize<Dictionary<string, object>>(existing);
                    if (doc != null && doc.ContainsKey("enabledMods"))
                    {
                        var arr = doc["enabledMods"] as IEnumerable;
                        if (arr != null)
                        {
                            foreach (object item in arr)
                            {
                                string id = Convert.ToString(item);
                                if (id == modId) return;
                                mods.Add(id);
                            }
                        }
                    }
                }
            }
            mods.Add(modId);
            var sb = new StringBuilder();
            sb.Append("{\n  \"enabledMods\": [\n");
            for (int i = 0; i < mods.Count; i++)
            {
                string escaped = mods[i].Replace("\\", "\\\\").Replace("\"", "\\\"");
                sb.Append("    \"").Append(escaped).Append(i == mods.Count - 1 ? "\"\n" : "\",\n");
            }
            sb.Append("  ]\n}\n");
            File.WriteAllText(enabledModsPath, sb.ToString(), new UTF8Encoding(false));
        }
    }

    internal sealed class InstallerForm : Form
    {
        private static readonly Color BgColor = Color.FromArgb(11, 22, 34);
        private static readonly Color CardColor = Color.FromArgb(17, 34, 51);
        private static readonly Color FieldColor = Color.FromArgb(9, 19, 29);
        private static readonly Color BorderColor = Color.FromArgb(53, 151, 184);
        private static readonly Color TextColor = Color.FromArgb(230, 246, 255);
        private static readonly Color MutedColor = Color.FromArgb(143, 181, 204);
        private static readonly Color SecondaryBg = Color.FromArgb(23, 69, 87);
        private static readonly Color PrimaryBg = Color.FromArgb(255, 158, 44);
        private static readonly Color PrimaryFg = Color.FromArgb(10, 17, 24);
        private static readonly Color OkColor = Color.FromArgb(82, 224, 124);
        private static readonly Color WarnColor = Color.FromArgb(255, 179, 64);

        private readonly Label subtitleLabel;
        private readonly Label fieldLabel;
        private readonly TextBox pathBox;
        private readonly Button browseButton;
        private readonly Button langButton;
        private readonly CheckBox wingmanBox;
        private readonly CheckBox russianBox;
        private readonly Label statusLabel;
        private readonly ProgressBar progressBar;
        private readonly Button installButton;
        private readonly Button launchOnlyButton;
        private readonly bool autoStart;
        private bool russian;
        private bool busy;

        public int ExitCode { get; private set; }

        public InstallerForm(string initialInstallRoot, bool autoStart, bool initialWingman)
        {
            this.autoStart = autoStart;
            this.russian = true;
            Text = "Starsector Coop Launcher";
            ClientSize = new Size(580, 336);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = BgColor;
            ForeColor = TextColor;
            Font = new Font("Segoe UI", 9.5f);
            try { Icon = Icon.ExtractAssociatedIcon(Program.SelfExe()); } catch { }

            var title = new Label
            {
                Text = "Starsector Coop",
                Font = new Font("Segoe UI", 16f, FontStyle.Bold),
                ForeColor = TextColor,
                Location = new Point(24, 18),
                AutoSize = true
            };
            langButton = MakeButton(russian ? "EN" : "RU", SecondaryBg, Color.FromArgb(203, 245, 255), BorderColor, 48, 26);
            langButton.Location = new Point(508, 20);
            langButton.Click += (s, e) => { russian = !russian; ApplyLanguage(); };

            subtitleLabel = new Label
            {
                ForeColor = MutedColor,
                Location = new Point(25, 50),
                Size = new Size(530, 22)
            };

            fieldLabel = new Label
            {
                Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
                ForeColor = TextColor,
                Location = new Point(24, 84),
                AutoSize = true
            };

            pathBox = new TextBox
            {
                Location = new Point(24, 108),
                Size = new Size(410, 28),
                BackColor = FieldColor,
                ForeColor = TextColor,
                BorderStyle = BorderStyle.FixedSingle,
                ReadOnly = true,
                Text = initialInstallRoot != null ? Path.Combine(initialInstallRoot, "starsector.exe") : ""
            };

            browseButton = MakeButton("Browse .exe", SecondaryBg, Color.FromArgb(203, 245, 255), BorderColor, 114, 28);
            browseButton.Location = new Point(442, 106);
            browseButton.Click += (s, e) => BrowseExe();

            wingmanBox = new CheckBox
            {
                Visible = false,
                Checked = false
            };

            russianBox = new CheckBox
            {
                ForeColor = TextColor,
                BackColor = BgColor,
                Location = new Point(24, 150),
                Size = new Size(532, 24),
                AutoCheck = true,
                FlatStyle = FlatStyle.Standard,
                Cursor = Cursors.Hand,
                Checked = false
            };

            statusLabel = new Label
            {
                ForeColor = initialInstallRoot != null ? OkColor : WarnColor,
                Location = new Point(24, 200),
                Size = new Size(532, 34)
            };

            progressBar = new ProgressBar
            {
                Location = new Point(24, 238),
                Size = new Size(532, 14),
                Minimum = 0,
                Maximum = 100,
                Style = ProgressBarStyle.Continuous
            };

            var footer = new Panel
            {
                Location = new Point(0, 272),
                Size = new Size(580, 64),
                BackColor = CardColor
            };

            launchOnlyButton = MakeButton("Launch installed", SecondaryBg, Color.FromArgb(203, 245, 255), BorderColor, 168, 36);
            launchOnlyButton.Location = new Point(24, 14);
            launchOnlyButton.Click += (s, e) => LaunchExisting();

            installButton = MakeButton("Install & Launch", PrimaryBg, PrimaryFg, Color.FromArgb(255, 196, 112), 204, 36);
            installButton.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
            installButton.Location = new Point(352, 14);
            installButton.Click += async (s, e) => await RunInstallAsync();

            footer.Controls.Add(launchOnlyButton);
            footer.Controls.Add(installButton);

            Controls.Add(title);
            Controls.Add(langButton);
            Controls.Add(subtitleLabel);
            Controls.Add(fieldLabel);
            Controls.Add(pathBox);
            Controls.Add(browseButton);
            Controls.Add(wingmanBox);
            Controls.Add(russianBox);
            Controls.Add(statusLabel);
            Controls.Add(progressBar);
            Controls.Add(footer);

            ApplyLanguage();
            Shown += async (s, e) =>
            {
                if (this.autoStart && !string.IsNullOrWhiteSpace(pathBox.Text))
                {
                    await RunInstallAsync();
                }
            };
        }

        private string Tr(string en, string ru)
        {
            return russian ? ru : en;
        }

        private void ApplyLanguage()
        {
            langButton.Text = russian ? "EN" : "RU";
            subtitleLabel.Text = Tr(
                "Select starsector.exe — downloads the latest release from GitHub, patches vmparams & launches.",
                "Укажите starsector.exe — скачает релиз с GitHub, пропатчит vmparams и запустит лаунчер.");
            fieldLabel.Text = Tr("Starsector executable (starsector.exe)", "Файл игры (starsector.exe)");
            browseButton.Text = Tr("Browse .exe", "Выбрать .exe");
            wingmanBox.Text = Tr(
                "Also install The Wingman Project (optional: manual co-op piloting)",
                "Также установить The Wingman Project (опционально: ручное пилотирование)");
            russianBox.Text = Tr(
                "Also install Starsector 0.98.5a-RC9 Russian translation (auto-download)",
                "Также установить русификатор Starsector 0.98.5a-RC9 (автоскачивание)");
            launchOnlyButton.Text = Tr("Launch installed", "Запустить лаунчер");
            if (!busy)
            {
                string root = Program.ResolveInstallRoot(pathBox.Text);
                statusLabel.ForeColor = root != null ? OkColor : WarnColor;
                statusLabel.Text = root != null
                    ? (Program.IsModReady(root)
                        ? Tr("Starsector Coop is installed. Click Launch or Update from GitHub.",
                             "Starsector Coop установлен. Нажмите «Запустить» или «Обновить с GitHub».")
                        : Tr("Starsector found. Click Install & Launch to download and configure the mod.",
                             "Игра найдена. Нажмите «Установить и запустить» для скачивания и настройки."))
                    : Tr("Choose your starsector.exe file to begin.", "Выберите файл starsector.exe, чтобы начать.");
            }
            UpdateButtons();
        }

        private static Button MakeButton(string text, Color bg, Color fg, Color border, int w, int h)
        {
            var b = new Button
            {
                Text = text,
                Size = new Size(w, h),
                BackColor = bg,
                ForeColor = fg,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand
            };
            b.FlatAppearance.BorderColor = border;
            b.FlatAppearance.BorderSize = 1;
            return b;
        }

        private void BrowseExe()
        {
            using (var dlg = new OpenFileDialog())
            {
                dlg.Title = "Select starsector.exe";
                dlg.Filter = "Starsector (starsector.exe)|starsector.exe|All files (*.*)|*.*";
                dlg.FileName = "starsector.exe";
                dlg.CheckFileExists = true;
                string existing = Program.ResolveInstallRoot(pathBox.Text) ?? Program.FindAnyStarsectorInstall();
                if (existing != null && Directory.Exists(existing))
                {
                    dlg.InitialDirectory = existing;
                }
                if (dlg.ShowDialog(this) != DialogResult.OK) return;

                string root = Program.ResolveInstallRoot(dlg.FileName);
                if (root == null)
                {
                    MessageBox.Show(this,
                        "Selected file is not in a valid Starsector folder (starsector.exe, vmparams and starsector-core required).",
                        "Starsector Coop Launcher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                pathBox.Text = Path.Combine(root, "starsector.exe");
                statusLabel.ForeColor = OkColor;
                statusLabel.Text = Program.IsModReady(root)
                    ? "Starsector Coop is installed here. Click Launch or Install / Update."
                    : "Starsector found. Click Install & Launch to download and configure the mod.";
                UpdateButtons();
            }
        }

        private void UpdateButtons()
        {
            string root = Program.ResolveInstallRoot(pathBox.Text);
            bool valid = root != null;
            bool ready = valid && Program.IsModReady(root);
            browseButton.Enabled = !busy;
            wingmanBox.Enabled = !busy;
            russianBox.Enabled = !busy;
            langButton.Enabled = !busy;
            installButton.Enabled = !busy;
            installButton.Text = valid
                ? (ready ? Tr("Update & Launch", "Обновить и запустить") : Tr("Install & Launch", "Установить и запустить"))
                : Tr("Select starsector.exe", "Выбрать starsector.exe");
            launchOnlyButton.Visible = ready;
            launchOnlyButton.Enabled = !busy && ready;
        }

        private void LaunchExisting()
        {
            string root = Program.ResolveInstallRoot(pathBox.Text);
            if (root == null || !Program.IsModReady(root)) return;
            Program.ApplyPendingJars(Program.GetModRoot(root));
            Program.LaunchJavaLauncher(root);
            ExitCode = 0;
            Close();
        }

        private async Task RunInstallAsync()
        {
            if (busy) return;
            string root = Program.ResolveInstallRoot(pathBox.Text);
            if (root == null)
            {
                BrowseExe();
                root = Program.ResolveInstallRoot(pathBox.Text);
                if (root == null) return;
            }

            if (!Program.TestDirWritable(root))
            {
                if (!Program.IsAdministrator())
                {
                    var ans = MessageBox.Show(this,
                        "The Starsector folder requires Administrator rights (e.g. Program Files).\n\nRestart the installer as Administrator to install Starsector Coop?",
                        "Administrator rights required", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                    if (ans != DialogResult.Yes) return;
                    try
                    {
                        Process.Start(new ProcessStartInfo
                        {
                            FileName = Program.SelfExe(),
                            Arguments = "--install \"" + Path.Combine(root, "starsector.exe") + "\""
                                + (wingmanBox.Checked ? " --with-wingman" : ""),
                            Verb = "runas",
                            UseShellExecute = true
                        });
                        ExitCode = 0;
                        Close();
                    }
                    catch (Exception ex)
                    {
                        statusLabel.ForeColor = WarnColor;
                        statusLabel.Text = "Elevation cancelled: " + ex.Message;
                    }
                    return;
                }
            }

            busy = true;
            UpdateButtons();
            progressBar.Value = 0;
            statusLabel.ForeColor = TextColor;
            statusLabel.Text = Tr("Starting installation...", "Начинаем установку...");

            bool includeWingman = wingmanBox.Checked;
            bool includeRussian = russianBox.Checked;
            try
            {
                string version = await Task.Run(() =>
                {
                    Action<string, int> report = (msg, pct) =>
                    {
                        if (IsDisposed) return;
                        BeginInvoke((Action)(() =>
                        {
                            statusLabel.Text = msg;
                            if (pct >= 0 && pct <= 100) progressBar.Value = pct;
                        }));
                    };
                    string v = Program.InstallAndConfigure(root, includeWingman, report);
                    if (includeRussian)
                    {
                        Program.InstallRussianTranslation(root, report);
                    }
                    return v;
                });

                statusLabel.ForeColor = OkColor;
                statusLabel.Text = "Installed v" + version + "! Starting Coop Launcher...";
                Program.ApplyPendingJars(Program.GetModRoot(root));
                Program.LaunchJavaLauncher(root);
                ExitCode = 0;
                Close();
            }
            catch (Exception ex)
            {
                ExitCode = 1;
                statusLabel.ForeColor = WarnColor;
                statusLabel.Text = "Install failed: " + ex.Message;
                MessageBox.Show(this, ex.Message, "Starsector Coop Installer", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                busy = false;
                if (!IsDisposed) UpdateButtons();
            }
        }
    }
}
