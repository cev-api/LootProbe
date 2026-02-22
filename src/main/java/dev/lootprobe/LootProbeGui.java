package dev.lootprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LootProbeGui {
    private static final Pattern TRANSLATE_KEY_PATTERN = Pattern.compile("\"translate\":\"([^\"]+)\"");
    private static final Pattern STORED_ENCHANTS_PATTERN = Pattern.compile("stored-enchants=\\{([^}]*)}");
    private static final Pattern DIRECT_ENCHANTS_PATTERN = Pattern.compile("enchants=\\{([^}]*)}");
    private static final Pattern POTION_TYPE_PATTERN = Pattern.compile("potion-type=([a-z0-9_:.]+)");
    private static final Pattern POTION_CONTENTS_PATTERN = Pattern.compile("potion:\\s*\"([a-z0-9_:.]+)\"");
    private static final Pattern SEARCH_TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");
    private static final String RESULT_FILTER_ANY = "(Any)";
    private static final String DEFAULT_CUBIOMES_DLL = "cubiomes.dll";
    private static final String DEFAULT_CUBIOMES_BRIDGE = "cubiomes_bridge.dll";

    private static final List<String> OVERWORLD_STRUCTURES = List.of(
            "minecraft:ancient_city", "minecraft:buried_treasure", "minecraft:desert_pyramid",
            "minecraft:igloo", "minecraft:jungle_temple", "minecraft:mansion", "minecraft:mineshaft",
            "minecraft:monument", "minecraft:ocean_ruin", "minecraft:pillager_outpost",
            "minecraft:ruined_portal", "minecraft:shipwreck", "minecraft:stronghold",
            "minecraft:swamp_hut", "minecraft:trail_ruins", "minecraft:trial_chambers",
            "minecraft:village"
    );
    private static final List<String> NETHER_STRUCTURES = List.of(
            "minecraft:bastion_remnant", "minecraft:fortress", "minecraft:nether_fossil",
            "minecraft:ruined_portal"
    );
    private static final List<String> END_STRUCTURES = List.of(
            "minecraft:end_city"
    );

    private final JFrame frame = new JFrame("Loot Probe");
    private final JTextField mcVersion = new JTextField("1.21.11");
    private final JTextField seed = new JTextField("000000000000000");
    private final JTextField serverJar = new JTextField();
    private final JTextField output = new JTextField("probe-result.json");
    private final JTextField workDir = new JTextField();

    private final JCheckBox autoDatapackStructures = new JCheckBox("Auto include datapack structures", false);
    private final JCheckBox ultraLean = new JCheckBox("Ultra-lean runtime config", true);

    private final JComboBox<String> dimensionDropdown = new JComboBox<>(new String[]{
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
    });
    private final JTextField scanCenterX = new JTextField("0");
    private final JTextField scanCenterZ = new JTextField("0");
    private final JTextField scanRadius = new JTextField("12500");
    private final JTextField locateStep = new JTextField("1200");
    private final JTextField extractChunkRadius = new JTextField("5");
    private final JCheckBox extractParallelChunks = new JCheckBox("Parallel Chunk Prefetch", true);
    private final JSlider extractParallelChunkCount = slider(1, 12, 4);
    private final JSlider extractParallelStructures = slider(1, 8, 1);
    private final JTextField extractTimeout = new JTextField("90");
    private final JTextField extractStartTimeoutMs = new JTextField("8000");
    private final JTextField extractStatusTimeoutMs = new JTextField("12000");
    private final JTextField maxStructures = new JTextField();
    private final JTextField pluginJar = new JTextField();
    private final JCheckBox cubiomesMap = new JCheckBox("Use Cubiomes biome map", true);
    private final JCheckBox cubiomesStructures = new JCheckBox("Use Cubiomes structure preview", false);
    private final JTextField mapMaxZoomOut = new JTextField("15000");
    private final JTextField mapIconScale = new JTextField("1.0");

    private final JTextField startupTimeout = new JTextField("180");
    private final JTextField datapackPath = new JTextField();

    private final JTextArea selectedStructures = new JTextArea(8, 40);
    private final JTextArea appLog = new JTextArea();
    private final JTextArea serverLog = new JTextArea();
    private final JTextArea resultDetails = new JTextArea();
    private final JLabel resultSummary = new JLabel("No results loaded.");
    private final JTextField itemSearch = new JTextField();
    private final JComboBox<String> resultDimensionFilter = new JComboBox<>(new String[]{RESULT_FILTER_ANY});
    private final JComboBox<String> resultStructureFilter = new JComboBox<>(new String[]{RESULT_FILTER_ANY});
    private final DefaultListModel<String> resultStructureModel = new DefaultListModel<>();
    private final JList<String> resultStructureList = new JList<>(resultStructureModel);
    private final DefaultListModel<String> resultChestModel = new DefaultListModel<>();
    private final JList<String> resultChestList = new JList<>(resultChestModel);

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final Map<String, JCheckBox> overworldStructureChecks = new LinkedHashMap<>();
    private final Map<String, JCheckBox> netherStructureChecks = new LinkedHashMap<>();
    private final Map<String, JCheckBox> endStructureChecks = new LinkedHashMap<>();
    private final JPanel datapackTargetsPanel = new JPanel();
    private final Map<String, DatapackTargetRow> datapackTargetRows = new LinkedHashMap<>();
    private final CubiomesBridge cubiomesBridge = new CubiomesBridge();
    private final MapPreviewPanel mapPanel = new MapPreviewPanel(this::applyScanCircleFromMap, cubiomesBridge);

    private final ModrinthClient modrinthClient = new ModrinthClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path settingsFile = Path.of(".lootprobe-gui-settings.json").toAbsolutePath();
    private volatile SwingWorker<ProbeResult, Void> currentWorker;
    private ProbeResult currentResult;
    private final List<Integer> filteredStructureIndexes = new ArrayList<>();
    private final List<Integer> filteredChestIndexes = new ArrayList<>();

    private LootProbeGui() {
    }

    public static void launch() {
        configureGraphicsPipeline();
        SwingUtilities.invokeLater(() -> new LootProbeGui().init(null));
    }

    public static void launchBlocking() throws InterruptedException {
        configureGraphicsPipeline();
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> new LootProbeGui().init(latch));
        latch.await();
    }

    private static void configureGraphicsPipeline() {
        if (System.getProperty("sun.java2d.opengl") == null) {
            System.setProperty("sun.java2d.opengl", "true");
        }
        if (System.getProperty("sun.java2d.d3d") == null) {
            System.setProperty("sun.java2d.d3d", "true");
        }
    }

    private void init(CountDownLatch closeLatch) {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveSettingsQuietly();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                ProbeRunner.shutdownReusableServer(new ProbeListener() {
                    @Override
                    public void onInfo(String message) {
                        log(appLog, message);
                    }
                });
                if (closeLatch != null) {
                    closeLatch.countDown();
                }
            }
        });

        frame.setLayout(new BorderLayout(8, 8));
        frame.setPreferredSize(new Dimension(1460, 920));

        selectedStructures.setText(formatTargetLine("minecraft:ancient_city", "minecraft:overworld"));
        appLog.setEditable(false);
        serverLog.setEditable(false);
        resultDetails.setEditable(false);
        configureTooltips();
        resultStructureList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultChestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        wireSearchFilters();
        wireResultSelection();
        wireMcVersionAutoDetect();
        wireScanMapSync();
        wireCubiomesOptions();
        loadSettingsQuietly();
        extractParallelChunks.addActionListener(e -> updateParallelControlsEnabled());
        updateParallelControlsEnabled();
        syncScanOverlayToMap();
        syncMapPreviewOptions();

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        left.add(buildActionBar(), BorderLayout.NORTH);
        left.add(buildConfigTabs(), BorderLayout.CENTER);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("Progress", new JScrollPane(appLog));
        rightTabs.addTab("Server Log", new JScrollPane(serverLog));
        rightTabs.addTab("Map", mapPanel);
        rightTabs.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (rightTabs.getSelectedComponent() == mapPanel) {
                    updateMapSelectionFromUi();
                    mapPanel.repaint();
                }
            }
        });
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        right.add(rightTabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.53);
        frame.add(split, BorderLayout.CENTER);

        rebuildTargetSelectorsFromText();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildActionBar() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Run"));

        progressBar.setStringPainted(true);
        progressBar.setString("idle");
        panel.add(progressBar);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton runButton = new JButton("Run");
        JButton cancel = new JButton("Cancel");
        JButton clearLogs = new JButton("Clear Logs");
        JButton clearSelections = new JButton("Reset Targets");
        JButton loadJson = new JButton("Load JSON");
        JButton exportSimplified = new JButton("Export Simplified");
        runButton.addActionListener(e -> runProbe(runButton));
        cancel.addActionListener(e -> cancelRun());
        clearLogs.addActionListener(e -> {
            appLog.setText("");
            serverLog.setText("");
        });
        clearSelections.addActionListener(e -> {
            String dim = String.valueOf(dimensionDropdown.getSelectedItem());
            selectedStructures.setText(formatTargetLine("minecraft:ancient_city", dim));
            rebuildTargetSelectorsFromText();
            scanCenterX.setText("0");
            scanCenterZ.setText("0");
            scanRadius.setText("12500");
        });
        loadJson.addActionListener(e -> loadResultJson());
        exportSimplified.addActionListener(e -> exportSimplifiedResults());
        row.add(runButton);
        row.add(cancel);
        row.add(clearLogs);
        row.add(clearSelections);
        row.add(loadJson);
        row.add(exportSimplified);
        panel.add(row);
        return panel;
    }

    private JTabbedPane buildConfigTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Core", buildCorePanel());
        tabs.addTab("Scan", buildScanPanel());
        tabs.addTab("Targets", buildTargetsPanel());
        tabs.addTab("Results", buildResultsPanel());
        return tabs;
    }

    private JPanel buildCorePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        addFormRow(form, "MC Version (download fallback)", mcVersion);
        addFormRow(form, "Seed", seed);
        addFormRow(form, "Output JSON", output);
        addFormRow(form, "Work Dir (optional)", workDir);
        addFormRow(form, "Startup Timeout (sec)", startupTimeout);
        addFormRow(form, "Dimension", dimensionDropdown);
        addFormRow(form, "Ultra Lean", ultraLean);

        JPanel jarPanel = new JPanel(new BorderLayout(6, 6));
        jarPanel.setBorder(BorderFactory.createTitledBorder("Server Jar"));
        jarPanel.add(serverJar, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton importJar = new JButton("Import");
        importJar.addActionListener(e -> {
            chooseFile(serverJar, false, "jar");
            autoDetectMcVersionFromJar(false);
        });
        JButton officialJar = new JButton("Download Official");
        officialJar.addActionListener(e -> downloadOfficialJar());
        JButton modrinthJar = new JButton("Download Modrinth");
        modrinthJar.addActionListener(e -> downloadJarFromModrinth());
        JButton chooseOutput = new JButton("Choose Output");
        chooseOutput.addActionListener(e -> chooseSaveFile(output, "json"));
        row.add(importJar);
        row.add(officialJar);
        row.add(modrinthJar);
        row.add(chooseOutput);
        jarPanel.add(row, BorderLayout.SOUTH);

        JPanel filesPanel = new JPanel(new GridLayout(0, 1, 8, 8));
        filesPanel.add(jarPanel);
        filesPanel.add(buildDatapacksPanel());
        panel.add(form, BorderLayout.NORTH);
        panel.add(filesPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildScanPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addScanRow(form, row++, "Scan Center X", scanCenterX);
        addScanRow(form, row++, "Scan Center Z", scanCenterZ);
        addScanRow(form, row++, "Scan Radius", scanRadius);
        addScanRow(form, row++, "Locate Step", locateStep);
        addScanRow(form, row++, "Extract Chunk Radius", extractChunkRadius);
        addScanRow(form, row++, "Parallel Extract", extractParallelChunks);
        addScanRow(form, row++, "Parallel Chunk Count", extractParallelChunkCount);
        addScanRow(form, row++, "Parallel Structures", extractParallelStructures);
        addScanRow(form, row++, "Extract Timeout (sec)", extractTimeout);
        addScanRow(form, row++, "Extract Start Timeout (ms)", extractStartTimeoutMs);
        addScanRow(form, row++, "Extract Status Timeout (ms)", extractStatusTimeoutMs);
        addScanRow(form, row++, "Max Structures (optional)", maxStructures);
        addScanRow(form, row++, "Auto Datapack Structures", autoDatapackStructures);
        addScanRow(form, row++, "Cubiomes Biome Map", cubiomesMap);
        addScanRow(form, row++, "Cubiomes Structures", cubiomesStructures);
        addScanRow(form, row++, "Map Max Zoom-Out (blocks)", mapMaxZoomOut);
        addScanRow(form, row++, "Map Icon Scale", mapIconScale);
        panel.add(form, BorderLayout.NORTH);

        JPanel pluginPanel = new JPanel(new BorderLayout(6, 6));
        pluginPanel.setBorder(BorderFactory.createTitledBorder("Paper Plugin Jar (scan mode)"));
        pluginPanel.add(pluginJar, BorderLayout.NORTH);
        JButton choosePlugin = new JButton("Choose Plugin Jar");
        choosePlugin.addActionListener(e -> chooseFile(pluginJar, false, "jar"));
        pluginPanel.add(choosePlugin, BorderLayout.SOUTH);
        panel.add(pluginPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildDatapacksPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Datapack"));
        panel.add(datapackPath, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton pickLocal = new JButton("Import Local");
        pickLocal.addActionListener(e -> importDatapack());
        JButton fromModrinth = new JButton("Download Modrinth");
        fromModrinth.addActionListener(e -> downloadDatapackFromModrinth());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clearDatapack());
        buttons.add(pickLocal);
        buttons.add(fromModrinth);
        buttons.add(clear);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildTargetsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.add(buildVanillaDimensionPanel("Overworld Structures", OVERWORLD_STRUCTURES, "minecraft:overworld", overworldStructureChecks));
        grid.add(buildVanillaDimensionPanel("Nether Structures", NETHER_STRUCTURES, "minecraft:the_nether", netherStructureChecks));
        grid.add(buildVanillaDimensionPanel("End Structures", END_STRUCTURES, "minecraft:the_end", endStructureChecks));
        grid.add(buildDatapackTargetsPanel());
        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildVanillaDimensionPanel(
            String title,
            List<String> structures,
            String dimension,
            Map<String, JCheckBox> targetMap
    ) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        targetMap.clear();
        for (String id : structures) {
            JCheckBox check = new JCheckBox(id, false);
            check.addActionListener(e -> syncSelectedTargetsTextFromUi());
            targetMap.put(id, check);
            list.add(check);
        }
        JPanel hint = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        hint.add(new JLabel("Dimension: " + dimension));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDatapackTargetsPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Datapack Structures (choose dimension)"));
        datapackTargetsPanel.setLayout(new BoxLayout(datapackTargetsPanel, BoxLayout.Y_AXIS));
        datapackTargetsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.add(new JScrollPane(datapackTargetsPanel), BorderLayout.CENTER);
        JButton refresh = new JButton("Refresh Datapack Structures");
        refresh.addActionListener(e -> refreshDatapackTargetRows());
        panel.add(refresh, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel topBar = new JPanel(new GridLayout(4, 1, 4, 4));
        topBar.add(resultSummary);
        JPanel searchRow = new JPanel(new GridLayout(1, 2, 6, 0));
        searchRow.add(new JLabel("Search (all terms)"));
        searchRow.add(itemSearch);
        topBar.add(searchRow);
        JPanel filterRow = new JPanel(new GridLayout(1, 4, 6, 0));
        filterRow.add(new JLabel("Dimension"));
        filterRow.add(resultDimensionFilter);
        filterRow.add(new JLabel("Structure"));
        filterRow.add(resultStructureFilter);
        topBar.add(filterRow);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton copyCoords = new JButton("Copy Coords");
        copyCoords.addActionListener(e -> copySelectedChestCoords());
        actions.add(copyCoords);
        topBar.add(actions);

        JPanel top = new JPanel(new GridLayout(1, 2, 8, 8));

        JPanel structuresPanel = new JPanel(new BorderLayout(4, 4));
        structuresPanel.setBorder(BorderFactory.createTitledBorder("Structures"));
        structuresPanel.add(new JScrollPane(resultStructureList), BorderLayout.CENTER);

        JPanel chestsPanel = new JPanel(new BorderLayout(4, 4));
        chestsPanel.setBorder(BorderFactory.createTitledBorder("Chests"));
        chestsPanel.add(new JScrollPane(resultChestList), BorderLayout.CENTER);

        top.add(structuresPanel);
        top.add(chestsPanel);

        JPanel detailsPanel = new JPanel(new BorderLayout(4, 4));
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Chest Items"));
        detailsPanel.add(new JScrollPane(resultDetails), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detailsPanel);
        split.setResizeWeight(0.5);
        panel.add(topBar, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void runProbe(JButton runButton) {
        if (currentWorker != null && !currentWorker.isDone()) {
            log(appLog, "A run is already in progress.");
            return;
        }

        ProbeConfig config;
        try {
            config = buildConfig();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Invalid Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }

        runButton.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("starting");
        mapPanel.setResult(config.seed, null);
        log(appLog, "Mode: scan=true, loot=false (chest extraction only)");

        currentWorker = new SwingWorker<>() {
            @Override
            protected ProbeResult doInBackground() throws Exception {
                ProbeRunner runner = new ProbeRunner();
                final long[] stageStartedMs = {System.currentTimeMillis()};
                final String[] stageName = {""};
                return runner.run(config, new ProbeListener() {
                    @Override
                    public void onInfo(String message) {
                        SwingUtilities.invokeLater(() -> log(appLog, message));
                    }

                    @Override
                    public void onProgress(String stage, int current, int total, String label) {
                        SwingUtilities.invokeLater(() -> {
                            String normalizedStage = stage != null ? stage : "";
                            if (!normalizedStage.equals(stageName[0])) {
                                stageName[0] = normalizedStage;
                                stageStartedMs[0] = System.currentTimeMillis();
                            }
                            int pct = total <= 0 ? 0 : (int) Math.round(current * 100.0 / total);
                            int clampedPct = Math.max(0, Math.min(100, pct));
                            int remainingPct = Math.max(0, 100 - clampedPct);

                            long nowMs = System.currentTimeMillis();
                            long elapsedMs = Math.max(1L, nowMs - stageStartedMs[0]);
                            long remainingMs = 0L;
                            if (total > 0 && current > 0 && current < total) {
                                double ratePerMs = current / (double) elapsedMs;
                                if (ratePerMs > 0.0) {
                                    remainingMs = Math.max(0L, Math.round((total - current) / ratePerMs));
                                }
                            }

                            long etaSeconds = remainingMs / 1000L;
                            long etaMin = etaSeconds / 60L;
                            long etaSec = etaSeconds % 60L;
                            String eta = String.format("~%02d:%02d remaining", etaMin, etaSec);

                            progressBar.setValue(clampedPct);
                            progressBar.setString(normalizedStage + " " + current + "/" + total
                                    + " | " + remainingPct + "% remaining | " + eta
                                    + (label != null && !label.isBlank() ? " | " + label : ""));
                        });
                    }

                    @Override
                    public void onServerLog(String line) {
                        SwingUtilities.invokeLater(() -> log(serverLog, line));
                    }
                });
            }

            @Override
            protected void done() {
                runButton.setEnabled(true);
                try {
                    ProbeResult result = get();
                    progressBar.setValue(100);
                    progressBar.setString("complete");
                    mapPanel.setResult(config.seed, result);
                    setCurrentResult(result);
                    log(appLog, "Probe complete: " + config.output.toAbsolutePath());
                } catch (Exception e) {
                    progressBar.setString("failed");
                    log(appLog, "Probe failed: " + e.getMessage());
                } finally {
                    saveSettingsQuietly();
                }
            }
        };
        currentWorker.execute();
    }

    private void cancelRun() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
            progressBar.setString("cancellation requested");
            log(appLog, "Cancellation requested.");
        }
    }

    private ProbeConfig buildConfig() {
        ProbeConfig config = new ProbeConfig();
        config.serverJar = blankToNullPath(serverJar.getText());
        String detectedMcVersion = autoDetectMcVersionFromJar(true);
        if (detectedMcVersion != null) {
            config.mcVersion = detectedMcVersion;
        } else {
            config.mcVersion = requireText(mcVersion, "MC Version");
        }
        config.seed = Long.parseLong(requireText(seed, "Seed"));
        config.output = Path.of(requireText(output, "Output JSON"));
        config.javaBin = "java";
        config.workDir = blankToNullPath(workDir.getText());
        config.startupTimeoutSec = Integer.parseInt(requireText(startupTimeout, "Startup Timeout"));
        String dimension = String.valueOf(dimensionDropdown.getSelectedItem());
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("Dimension is required.");
        }
        config.structureDimension = dimension;
        config.scanDimension = dimension;
        config.locateStep = Integer.parseInt(requireText(locateStep, "Locate Step"));
        config.extractChunkRadius = Integer.parseInt(requireText(extractChunkRadius, "Extract Chunk Radius"));
        config.extractParallelChunks = extractParallelChunks.isSelected();
        if (config.extractParallelChunks) {
            config.extractParallelChunkCount = extractParallelChunkCount.getValue();
            config.extractParallelStructureJobs = extractParallelStructures.getValue();
        } else {
            config.extractParallelChunkCount = 1;
            config.extractParallelStructureJobs = 1;
        }
        config.extractTimeoutSec = Integer.parseInt(requireText(extractTimeout, "Extract Timeout"));
        config.extractStartCommandTimeoutMs = Integer.parseInt(requireText(extractStartTimeoutMs, "Extract Start Timeout"));
        config.extractStatusReadTimeoutMs = Integer.parseInt(requireText(extractStatusTimeoutMs, "Extract Status Timeout"));
        config.maxStructures = blankToNullInt(maxStructures.getText());
        config.paperPluginJar = Path.of(requireText(pluginJar, "Paper Plugin Jar"));
        config.autoDatapackStructures = autoDatapackStructures.isSelected();
        config.ultraLean = ultraLean.isSelected();
        config.reuseServerIfPossible = true;
        config.datapacks = activeDatapacks();
        List<ProbeConfig.StructureTarget> targets = parseStructureTargets(selectedStructures.getText(), config.structureDimension);
        config.structureTargets = targets;
        config.structures = targets.stream().map(t -> t.id).distinct().toList();
        config.lootTables = new ArrayList<>();
        config.scanCenterX = Integer.parseInt(requireText(scanCenterX, "Scan Center X"));
        config.scanCenterZ = Integer.parseInt(requireText(scanCenterZ, "Scan Center Z"));
        config.scanRadius = Integer.parseInt(requireText(scanRadius, "Scan Radius"));
        if (config.structures.isEmpty()) {
            throw new IllegalArgumentException("At least one structure is required.");
        }
        return config;
    }

    private String autoDetectMcVersionFromJar(boolean strictWhenJarSet) {
        Path jarPath = blankToNullPath(serverJar.getText());
        if (jarPath == null) {
            return null;
        }
        try {
            Optional<String> detected = MinecraftVersionDetector.detect(jarPath);
            if (detected.isPresent()) {
                String version = detected.get();
                if (!version.equals(mcVersion.getText().trim())) {
                    mcVersion.setText(version);
                }
                return version;
            }
        } catch (Exception e) {
            log(appLog, "MC version detect failed: " + e.getMessage());
        }
        if (strictWhenJarSet && mcVersion.getText().trim().isBlank()) {
            throw new IllegalArgumentException("Could not detect MC version from Server Jar. Set MC Version manually.");
        }
        return null;
    }

    private void importDatapack() {
        JFileChooser chooser = newFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Datapack zip/folder", "zip"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        setDatapackPath(chooser.getSelectedFile().toPath().toAbsolutePath());
        refreshDatapackTargetRows();
        saveSettingsQuietly();
    }

    private void downloadDatapackFromModrinth() {
        String query = JOptionPane.showInputDialog(frame, "Datapack name / slug:", "Download Datapack", JOptionPane.QUESTION_MESSAGE);
        if (query == null || query.isBlank()) {
            return;
        }
        try {
            List<ModrinthClient.ProjectHit> hits = modrinthClient.searchProjects(query.trim(), "datapack");
            if (hits.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "No datapack projects found.", "Modrinth", JOptionPane.WARNING_MESSAGE);
                return;
            }
            ModrinthClient.ProjectHit chosen = pickProject("Choose datapack", hits);
            if (chosen == null) {
                return;
            }
            String version = requireText(mcVersion, "MC Version");
            Path downloaded = modrinthClient.downloadProjectFile(chosen.projectId, version, ".zip", Path.of("downloads", "datapacks"));
            setDatapackPath(downloaded.toAbsolutePath());
            log(appLog, "Downloaded datapack: " + downloaded.toAbsolutePath());
            refreshDatapackTargetRows();
            saveSettingsQuietly();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Download failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void downloadJarFromModrinth() {
        String query = JOptionPane.showInputDialog(frame, "Server project name/slug (ex: paper):", "Download Server Jar", JOptionPane.QUESTION_MESSAGE);
        if (query == null || query.isBlank()) {
            return;
        }
        try {
            String version = requireText(mcVersion, "MC Version");
            Path downloaded = modrinthClient.downloadBySlug(query.trim(), version, ".jar", Path.of("downloads", "server-jars"));
            serverJar.setText(downloaded.toAbsolutePath().toString());
            autoDetectMcVersionFromJar(false);
            log(appLog, "Downloaded server jar: " + downloaded.toAbsolutePath());
            saveSettingsQuietly();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Download failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private ModrinthClient.ProjectHit pickProject(String title, List<ModrinthClient.ProjectHit> hits) {
        Object selected = JOptionPane.showInputDialog(
                frame,
                "Select one:",
                title,
                JOptionPane.PLAIN_MESSAGE,
                null,
                hits.toArray(),
                hits.get(0)
        );
        if (selected instanceof ModrinthClient.ProjectHit hit) {
            return hit;
        }
        return null;
    }

    private void clearDatapack() {
        setDatapackPath(null);
        refreshDatapackTargetRows();
        saveSettingsQuietly();
    }

    private void downloadOfficialJar() {
        try {
            String version = requireText(mcVersion, "MC Version");
            Path path = MojangVersionResolver.downloadServerJar(version, Path.of("downloads", "cache"));
            serverJar.setText(path.toAbsolutePath().toString());
            autoDetectMcVersionFromJar(false);
            log(appLog, "Downloaded official jar: " + path.toAbsolutePath());
            saveSettingsQuietly();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Download failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshDatapackTargetRows() {
        List<ProbeConfig.StructureTarget> selected = parseStructureTargets(selectedStructures.getText(), "minecraft:overworld");
        Map<String, String> selectedDatapackDims = new LinkedHashMap<>();
        Set<String> vanilla = new LinkedHashSet<>();
        vanilla.addAll(OVERWORLD_STRUCTURES);
        vanilla.addAll(NETHER_STRUCTURES);
        vanilla.addAll(END_STRUCTURES);
        for (ProbeConfig.StructureTarget target : selected) {
            if (!vanilla.contains(target.id)) {
                selectedDatapackDims.put(target.id, target.normalizedDimension("minecraft:overworld"));
            }
        }

        Set<String> datapackStructures = new LinkedHashSet<>();
        try {
            DatapackInspector.DatapackInfluence influence = DatapackInspector.inspect(activeDatapacks());
            datapackStructures.addAll(influence.addedStructures);
        } catch (Exception e) {
            log(appLog, "Datapack structure parse failed: " + e.getMessage());
        }

        datapackTargetsPanel.removeAll();
        datapackTargetRows.clear();
        for (String id : datapackStructures) {
            DatapackTargetRow row = new DatapackTargetRow(id);
            String dim = selectedDatapackDims.get(id);
            if (dim != null) {
                row.enabled.setSelected(true);
                row.dimension.setSelectedItem(dim);
            }
            row.enabled.addActionListener(e -> syncSelectedTargetsTextFromUi());
            row.dimension.addActionListener(e -> syncSelectedTargetsTextFromUi());
            datapackTargetRows.put(id, row);
            row.panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.panel.getPreferredSize().height));
            datapackTargetsPanel.add(row.panel);
        }
        datapackTargetsPanel.revalidate();
        datapackTargetsPanel.repaint();
        syncSelectedTargetsTextFromUi();
    }

    private void rebuildTargetSelectorsFromText() {
        List<ProbeConfig.StructureTarget> selected = parseStructureTargets(selectedStructures.getText(), "minecraft:overworld");
        Set<String> selectedPairs = new LinkedHashSet<>();
        for (ProbeConfig.StructureTarget target : selected) {
            selectedPairs.add(target.normalizedDimension("minecraft:overworld") + "|" + target.id);
        }

        applyChecks(overworldStructureChecks, selectedPairs, "minecraft:overworld");
        applyChecks(netherStructureChecks, selectedPairs, "minecraft:the_nether");
        applyChecks(endStructureChecks, selectedPairs, "minecraft:the_end");
        refreshDatapackTargetRows();
    }

    private static void applyChecks(Map<String, JCheckBox> checks, Set<String> selectedPairs, String dimension) {
        for (Map.Entry<String, JCheckBox> entry : checks.entrySet()) {
            String id = entry.getKey();
            JCheckBox check = entry.getValue();
            check.setSelected(selectedPairs.contains(dimension + "|" + id));
        }
    }

    private void syncSelectedTargetsTextFromUi() {
        List<String> lines = new ArrayList<>();
        appendCheckedTargets(lines, overworldStructureChecks, "minecraft:overworld");
        appendCheckedTargets(lines, netherStructureChecks, "minecraft:the_nether");
        appendCheckedTargets(lines, endStructureChecks, "minecraft:the_end");
        for (DatapackTargetRow row : datapackTargetRows.values()) {
            if (row.enabled.isSelected()) {
                String dim = String.valueOf(row.dimension.getSelectedItem());
                lines.add(formatTargetLine(row.id, dim));
            }
        }
        selectedStructures.setText(String.join("\n", lines));
    }

    private static void appendCheckedTargets(List<String> out, Map<String, JCheckBox> checks, String dimension) {
        for (Map.Entry<String, JCheckBox> entry : checks.entrySet()) {
            if (entry.getValue().isSelected()) {
                out.add(formatTargetLine(entry.getKey(), dimension));
            }
        }
    }

    private void configureTooltips() {
        mcVersion.setToolTipText("Auto-detected from Server Jar when available; otherwise used for downloads.");
        seed.setToolTipText("World seed used for locate and extraction.");
        output.setToolTipText("Output JSON file path for the scan result.");
        workDir.setToolTipText("Optional run directory. Leave empty to use temp (auto-cleaned).");
        startupTimeout.setToolTipText("Max seconds to wait for server startup before failing.");
        dimensionDropdown.setToolTipText("Dimension for locate/extraction (overworld, nether, end).");
        serverJar.setToolTipText("Path to server jar (MC version is auto-detected from this file).");
        scanCenterX.setToolTipText("Center X for bounded scan area.");
        scanCenterZ.setToolTipText("Center Z for bounded scan area.");
        scanRadius.setToolTipText("Scan radius in blocks from center.");
        locateStep.setToolTipText("Distance in blocks between locate sample points. Lower = slower but more exhaustive.");
        extractChunkRadius.setToolTipText("Chunk radius around each discovered structure to generate/read containers.");
        extractParallelChunks.setToolTipText("Prefetch multiple chunks per structure for faster extraction. Enabled by default. Not recommended above 10,000 radius if you need maximum accuracy (~99.7% at larger ranges).");
        extractParallelChunkCount.setToolTipText("Max in-flight chunk loads per structure (1-12).");
        extractParallelStructures.setToolTipText("How many structure extraction jobs to run in parallel (1-8).");
        extractTimeout.setToolTipText("Per-structure extraction timeout in seconds.");
        extractStartTimeoutMs.setToolTipText("RCON timeout for extract-start replies (ms). Increasing can help when using many datapack structures.");
        extractStatusTimeoutMs.setToolTipText("RCON timeout for extract-status polling (ms). Increase for very large datapack structure sets to avoid timeout failures.");
        maxStructures.setToolTipText("Optional cap on number of discovered structures to extract.");
        autoDatapackStructures.setToolTipText("Also include structures defined by loaded datapacks.");
        ultraLean.setToolTipText("Apply runtime gamerule/perf settings for faster probing.");
        pluginJar.setToolTipText("Path to lootprobe Paper plugin jar used for chest extraction.");
        cubiomesMap.setToolTipText("Render map background from real Cubiomes biome generation.");
        cubiomesStructures.setToolTipText("Overlay viable vanilla structure attempts from Cubiomes.");
        mapMaxZoomOut.setToolTipText("Maximum visible world span at full zoom-out (default 15000 blocks).");
        mapIconScale.setToolTipText("Scale multiplier for map markers/icons (default 1.0).");
        datapackPath.setToolTipText("Optional single datapack zip/folder for this run.");
        selectedStructures.setToolTipText("Internal selected targets cache.");
        datapackTargetsPanel.setToolTipText("Datapack structures with manually assigned dimensions.");
        itemSearch.setToolTipText("Space-separated AND search across chest/item data (example: protection iv chest).");
        resultDimensionFilter.setToolTipText("Filter result list by dimension.");
        resultStructureFilter.setToolTipText("Filter result list by structure id.");
        resultStructureList.setToolTipText("Filtered scanned structures.");
        resultChestList.setToolTipText("Filtered chests for selected structure.");
        resultDetails.setToolTipText("Details for selected chest.");
        mapPanel.setToolTipText(null);
    }

    private void wireResultSelection() {
        resultStructureList.addListSelectionListener(e -> {
            refreshChestList();
            SwingUtilities.invokeLater(this::updateMapSelectionFromUi);
        });
        resultChestList.addListSelectionListener(e -> {
            refreshChestDetails();
            SwingUtilities.invokeLater(this::updateMapSelectionFromUi);
        });
        MouseAdapter mouseSync = new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                SwingUtilities.invokeLater(LootProbeGui.this::updateMapSelectionFromUi);
            }
        };
        resultStructureList.addMouseListener(mouseSync);
        resultChestList.addMouseListener(mouseSync);
    }

    private void wireMcVersionAutoDetect() {
        serverJar.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                autoDetectMcVersionFromJar(false);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                autoDetectMcVersionFromJar(false);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                autoDetectMcVersionFromJar(false);
            }
        });
    }

    private void wireSearchFilters() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshStructureList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshStructureList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshStructureList();
            }
        };
        itemSearch.getDocument().addDocumentListener(listener);
        resultDimensionFilter.addActionListener(e -> refreshStructureList());
        resultStructureFilter.addActionListener(e -> refreshStructureList());
    }

    private void wireScanMapSync() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncScanOverlayToMap();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncScanOverlayToMap();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncScanOverlayToMap();
            }
        };
        scanCenterX.getDocument().addDocumentListener(listener);
        scanCenterZ.getDocument().addDocumentListener(listener);
        scanRadius.getDocument().addDocumentListener(listener);
    }

    private void wireCubiomesOptions() {
        cubiomesMap.addActionListener(e -> syncMapPreviewOptions());
        cubiomesStructures.addActionListener(e -> syncMapPreviewOptions());
        dimensionDropdown.addActionListener(e -> syncMapPreviewOptions());
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncMapPreviewOptions();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncMapPreviewOptions();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncMapPreviewOptions();
            }
        };
        mcVersion.getDocument().addDocumentListener(listener);
        selectedStructures.getDocument().addDocumentListener(listener);
        mapMaxZoomOut.getDocument().addDocumentListener(listener);
        mapIconScale.getDocument().addDocumentListener(listener);
    }

    private void syncMapPreviewOptions() {
        mapPanel.setCubiomesOptions(
                cubiomesMap.isSelected(),
                cubiomesStructures.isSelected(),
                parseIntSafe(mapMaxZoomOut.getText()) != null ? Math.max(2000, parseIntSafe(mapMaxZoomOut.getText())) : 15000,
                parseDoubleSafe(mapIconScale.getText()) != null ? Math.max(0.2, Math.min(6.0, parseDoubleSafe(mapIconScale.getText()))) : 1.0,
                mcVersion.getText().trim(),
                String.valueOf(dimensionDropdown.getSelectedItem()),
                parseStructureIdsForDimension(String.valueOf(dimensionDropdown.getSelectedItem()))
        );
    }

    private List<String> parseStructureIdsForDimension(String dimension) {
        List<ProbeConfig.StructureTarget> targets = parseStructureTargets(selectedStructures.getText(), "minecraft:overworld");
        List<String> out = new ArrayList<>();
        for (ProbeConfig.StructureTarget target : targets) {
            if (target == null || target.id == null || target.id.isBlank()) {
                continue;
            }
            if (target.normalizedDimension("minecraft:overworld").equals(dimension)) {
                out.add(target.id.trim());
            }
        }
        return out;
    }

    private void syncScanOverlayToMap() {
        Integer cx = parseIntSafe(scanCenterX.getText());
        Integer cz = parseIntSafe(scanCenterZ.getText());
        Integer r = parseIntSafe(scanRadius.getText());
        if (r != null && r < 1) {
            r = 1;
        }
        mapPanel.setScanCircle(cx, cz, r);
    }

    private void applyScanCircleFromMap(int centerX, int centerZ, int radius) {
        scanCenterX.setText(Integer.toString(centerX));
        scanCenterZ.setText(Integer.toString(centerZ));
        scanRadius.setText(Integer.toString(Math.max(1, radius)));
        syncScanOverlayToMap();
    }

    private void setCurrentResult(ProbeResult result) {
        this.currentResult = result;
        refreshResultFilterChoices();
        refreshStructureList();
        updateMapSelectionFromUi();
    }

    private void refreshResultFilterChoices() {
        String selectedDim = String.valueOf(resultDimensionFilter.getSelectedItem());
        String selectedStructure = String.valueOf(resultStructureFilter.getSelectedItem());
        Set<String> dimensions = new LinkedHashSet<>();
        Set<String> structures = new LinkedHashSet<>();
        for (WorldChestScanner.ScannedStructure structure : getScannedStructures()) {
            if (structure == null) {
                continue;
            }
            if (structure.dimension != null && !structure.dimension.isBlank()) {
                dimensions.add(structure.dimension);
            }
            if (structure.id != null && !structure.id.isBlank()) {
                structures.add(structure.id);
            }
        }
        resultDimensionFilter.removeAllItems();
        resultDimensionFilter.addItem(RESULT_FILTER_ANY);
        for (String dim : dimensions) {
            resultDimensionFilter.addItem(dim);
        }
        if (selectedDim != null && dimensions.contains(selectedDim)) {
            resultDimensionFilter.setSelectedItem(selectedDim);
        } else {
            resultDimensionFilter.setSelectedItem(RESULT_FILTER_ANY);
        }

        resultStructureFilter.removeAllItems();
        resultStructureFilter.addItem(RESULT_FILTER_ANY);
        for (String id : structures) {
            resultStructureFilter.addItem(id);
        }
        if (selectedStructure != null && structures.contains(selectedStructure)) {
            resultStructureFilter.setSelectedItem(selectedStructure);
        } else {
            resultStructureFilter.setSelectedItem(RESULT_FILTER_ANY);
        }
    }

    private void refreshStructureList() {
        int previousSelected = resultStructureList.getSelectedIndex();
        resultStructureModel.clear();
        filteredStructureIndexes.clear();
        List<WorldChestScanner.ScannedStructure> structures = getScannedStructures();
        List<String> queryTokens = parseQueryTokens(itemSearch.getText());
        String requiredDimension = selectedResultFilter(resultDimensionFilter);
        String requiredStructure = selectedResultFilter(resultStructureFilter);

        int totalChests = 0;
        int totalItems = 0;
        for (WorldChestScanner.ScannedStructure structure : structures) {
            if (structure.chests != null) {
                totalChests += structure.chests.size();
                for (WorldChestScanner.ChestData chest : structure.chests) {
                    totalItems += chest.items != null ? chest.items.size() : 0;
                }
            }
        }

        for (int i = 0; i < structures.size(); i++) {
            WorldChestScanner.ScannedStructure s = structures.get(i);
            if (!matchesStructure(s, queryTokens, requiredDimension, requiredStructure)) {
                continue;
            }
            filteredStructureIndexes.add(i);
            int chestCount = s.chests != null ? s.chests.size() : 0;
            int itemCount = 0;
            if (s.chests != null) {
                for (WorldChestScanner.ChestData chest : s.chests) {
                    itemCount += chest.items != null ? chest.items.size() : 0;
                }
            }
            String id = s.id != null ? s.id : "-";
            String dim = (s.dimension != null && !s.dimension.isBlank()) ? s.dimension : "-";
            resultStructureModel.addElement(String.format("#%d  %s  [%s]  @ (%d,%d)  chests=%d  items=%d",
                    i, id, dim, s.x, s.z, chestCount, itemCount));
        }
        resultSummary.setText(String.format("Structures: %d/%d   Chests: %d   Item stacks: %d",
                filteredStructureIndexes.size(), structures.size(), totalChests, totalItems));

        if (!filteredStructureIndexes.isEmpty()) {
            int next = previousSelected >= 0 && previousSelected < filteredStructureIndexes.size() ? previousSelected : 0;
            resultStructureList.setSelectedIndex(next);
        } else {
            resultChestModel.clear();
            if (structures.isEmpty()) {
                resultDetails.setText("No regionScan structures in current result.");
            } else {
                resultDetails.setText("No structures match current search filters.");
            }
        }
        updateMapSelectionFromUi();
    }

    private void refreshChestList() {
        int previousSelected = resultChestList.getSelectedIndex();
        resultChestModel.clear();
        filteredChestIndexes.clear();
        int selectedFilteredStructure = resultStructureList.getSelectedIndex();
        if (selectedFilteredStructure < 0 || selectedFilteredStructure >= filteredStructureIndexes.size()) {
            return;
        }
        int structureIndex = filteredStructureIndexes.get(selectedFilteredStructure);
        List<String> queryTokens = parseQueryTokens(itemSearch.getText());
        if (structureIndex < 0) {
            return;
        }
        List<WorldChestScanner.ScannedStructure> structures = getScannedStructures();
        if (structureIndex >= structures.size()) {
            return;
        }
        WorldChestScanner.ScannedStructure structure = structures.get(structureIndex);
        if (structure.chests == null || structure.chests.isEmpty()) {
            resultDetails.setText("No chests in selected structure.");
            return;
        }
        for (int i = 0; i < structure.chests.size(); i++) {
            WorldChestScanner.ChestData chest = structure.chests.get(i);
            if (!matchesChest(structure, chest, queryTokens)) {
                continue;
            }
            filteredChestIndexes.add(i);
            int items = chest.items != null ? chest.items.size() : 0;
            String table = chest.lootTable != null ? chest.lootTable : "-";
            resultChestModel.addElement(String.format("#%d  %s  @ (%d,%d,%d)  table=%s  items=%d",
                    i, chest.blockId, chest.x, chest.y, chest.z, table, items));
        }
        if (!filteredChestIndexes.isEmpty()) {
            int next = previousSelected >= 0 && previousSelected < filteredChestIndexes.size() ? previousSelected : 0;
            resultChestList.setSelectedIndex(next);
            refreshChestDetails();
        } else {
            resultDetails.setText("No chests match current filters for selected structure.");
        }
        updateMapSelectionFromUi();
    }

    private void refreshChestDetails() {
        int filteredStructureIndex = resultStructureList.getSelectedIndex();
        int filteredChestIndex = resultChestList.getSelectedIndex();
        if (filteredStructureIndex < 0 || filteredChestIndex < 0) {
            return;
        }
        if (filteredStructureIndex >= filteredStructureIndexes.size() || filteredChestIndex >= filteredChestIndexes.size()) {
            return;
        }
        int structureIndex = filteredStructureIndexes.get(filteredStructureIndex);
        int chestIndex = filteredChestIndexes.get(filteredChestIndex);
        List<WorldChestScanner.ScannedStructure> structures = getScannedStructures();
        if (structureIndex >= structures.size()) {
            return;
        }
        WorldChestScanner.ScannedStructure structure = structures.get(structureIndex);
        if (structure.chests == null || chestIndex >= structure.chests.size()) {
            return;
        }
        WorldChestScanner.ChestData chest = structure.chests.get(chestIndex);
        StringBuilder sb = new StringBuilder();
        sb.append("Structure (scan target): ").append(structure.id).append("\n");
        sb.append("Dimension: ").append(structure.dimension != null ? structure.dimension : "-").append("\n");
        sb.append("Chest: ").append(chest.blockId).append(" @ ").append(chest.x).append(",").append(chest.y).append(",").append(chest.z).append("\n");
        sb.append("Loot Table: ").append(chest.lootTable != null ? chest.lootTable : "-").append("\n");
        sb.append("\nGrouped Item Totals:\n");
        Map<String, Integer> grouped = new LinkedHashMap<>();
        if (chest.items == null || chest.items.isEmpty()) {
            sb.append("  (empty)\n");
        } else {
            for (LootSampler.ItemStackData item : chest.items) {
                String key = formatItemLabel(item);
                grouped.merge(key, item.count, Integer::sum);
            }
            grouped.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append("  - ").append(e.getKey()).append(" x").append(e.getValue()).append("\n"));
        }
        resultDetails.setText(sb.toString());
        resultDetails.setCaretPosition(0);
    }

    private void updateMapSelectionFromUi() {
        WorldChestScanner.ScannedStructure selectedStructure = null;
        WorldChestScanner.ChestData selectedChest = null;
        int filteredStructureIndex = resultStructureList.getSelectedIndex();
        if (filteredStructureIndex >= 0 && filteredStructureIndex < filteredStructureIndexes.size()) {
            int structureIndex = filteredStructureIndexes.get(filteredStructureIndex);
            List<WorldChestScanner.ScannedStructure> structures = getScannedStructures();
            if (structureIndex >= 0 && structureIndex < structures.size()) {
                selectedStructure = structures.get(structureIndex);
                int filteredChestIndex = resultChestList.getSelectedIndex();
                if (selectedStructure.chests != null && filteredChestIndex >= 0 && filteredChestIndex < filteredChestIndexes.size()) {
                    int chestIndex = filteredChestIndexes.get(filteredChestIndex);
                    if (chestIndex >= 0 && chestIndex < selectedStructure.chests.size()) {
                        selectedChest = selectedStructure.chests.get(chestIndex);
                    }
                }
            }
        }
        mapPanel.setSelection(selectedStructure, selectedChest);
    }

    private static String formatItemLabel(LootSampler.ItemStackData item) {
        String nbt = item.nbt != null ? item.nbt : "";
        String translateKey = findFirst(TRANSLATE_KEY_PATTERN, nbt);
        String base;
        if (translateKey != null) {
            base = prettifyTranslateKey(translateKey);
        } else if (item.displayName != null && !item.displayName.isBlank()) {
            base = titleFromSnake(item.displayName);
        } else {
            base = titleFromSnake(shortId(item.itemId));
        }

        String enchantText = parseEnchantText(nbt);
        String potionText = parsePotionText(nbt);
        if (!potionText.isBlank()) {
            base = base + " (" + potionText + ")";
        }
        if (!enchantText.isBlank()) {
            return base + " [" + enchantText + "]";
        }
        return base;
    }

    private static String parseEnchantText(String nbt) {
        String stored = findFirst(STORED_ENCHANTS_PATTERN, nbt);
        if (stored != null && !stored.isBlank()) {
            return prettifyEnchantMap(stored);
        }
        String direct = findFirst(DIRECT_ENCHANTS_PATTERN, nbt);
        if (direct != null && !direct.isBlank()) {
            return prettifyEnchantMap(direct);
        }
        return "";
    }

    private static String parsePotionText(String nbt) {
        String potionId = findFirst(POTION_TYPE_PATTERN, nbt);
        if (potionId == null || potionId.isBlank()) {
            potionId = findFirst(POTION_CONTENTS_PATTERN, nbt);
        }
        if (potionId == null || potionId.isBlank()) {
            return "";
        }
        return titleFromSnake(shortId(potionId));
    }

    private static String prettifyEnchantMap(String raw) {
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0 || eq >= s.length() - 1) {
                out.add(s);
                continue;
            }
            String id = s.substring(0, eq).trim();
            String levelRaw = s.substring(eq + 1).trim();
            String name = titleFromSnake(shortId(id));
            String level = levelRaw;
            try {
                level = toRoman(Integer.parseInt(levelRaw));
            } catch (NumberFormatException ignored) {
            }
            out.add(name + " " + level);
        }
        return String.join(", ", out);
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        int idx = id.indexOf(':');
        return idx >= 0 && idx + 1 < id.length() ? id.substring(idx + 1) : id;
    }

    private static String titleFromSnake(String input) {
        String s = input == null ? "-" : input.trim().replace('_', ' ');
        String[] words = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            if (i > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                out.append(w.substring(1));
            }
        }
        return out.toString();
    }

    private static String prettifyTranslateKey(String key) {
        if (key == null || key.isBlank()) {
            return "-";
        }
        int dot = key.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < key.length() ? key.substring(dot + 1) : key;
        return titleFromSnake(tail);
    }

    private static String findFirst(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input != null ? input : "");
        return m.find() ? m.group(1) : null;
    }

    private static String toRoman(int value) {
        if (value <= 0) {
            return Integer.toString(value);
        }
        int[] nums = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] rom = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int n = value;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.length; i++) {
            while (n >= nums[i]) {
                sb.append(rom[i]);
                n -= nums[i];
            }
        }
        return sb.toString();
    }

    private void copySelectedChestCoords() {
        int filteredStructureIndex = resultStructureList.getSelectedIndex();
        int filteredChestIndex = resultChestList.getSelectedIndex();
        if (filteredStructureIndex < 0 || filteredChestIndex < 0
                || filteredStructureIndex >= filteredStructureIndexes.size()
                || filteredChestIndex >= filteredChestIndexes.size()) {
            log(appLog, "No chest selected to copy coordinates.");
            return;
        }
        int structureIndex = filteredStructureIndexes.get(filteredStructureIndex);
        int chestIndex = filteredChestIndexes.get(filteredChestIndex);
        List<WorldChestScanner.ScannedStructure> structures = getScannedStructures();
        if (structureIndex < 0 || structureIndex >= structures.size()) {
            return;
        }
        WorldChestScanner.ScannedStructure structure = structures.get(structureIndex);
        if (structure.chests == null || chestIndex < 0 || chestIndex >= structure.chests.size()) {
            return;
        }
        WorldChestScanner.ChestData chest = structure.chests.get(chestIndex);
        String text = chest.x + " " + chest.y + " " + chest.z;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        log(appLog, "Copied chest coordinates: " + text);
    }

    private List<WorldChestScanner.ScannedStructure> getScannedStructures() {
        if (currentResult == null || currentResult.regionScan == null || currentResult.regionScan.structures == null) {
            return List.of();
        }
        return currentResult.regionScan.structures;
    }

    private static String selectedResultFilter(JComboBox<String> filter) {
        Object selected = filter.getSelectedItem();
        if (selected == null) {
            return null;
        }
        String value = selected.toString().trim();
        if (value.isEmpty() || RESULT_FILTER_ANY.equals(value)) {
            return null;
        }
        return value;
    }

    private static boolean matchesStructure(
            WorldChestScanner.ScannedStructure structure,
            List<String> queryTokens,
            String requiredDimension,
            String requiredStructure
    ) {
        if (structure == null) {
            return false;
        }
        if (requiredDimension != null && !requiredDimension.equals(structure.dimension)) {
            return false;
        }
        if (requiredStructure != null && !requiredStructure.equals(structure.id)) {
            return false;
        }
        if (queryTokens.isEmpty()) {
            return true;
        }
        if (structure.chests == null) {
            return false;
        }
        for (WorldChestScanner.ChestData chest : structure.chests) {
            if (matchesChest(structure, chest, queryTokens)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesChest(
            WorldChestScanner.ScannedStructure structure,
            WorldChestScanner.ChestData chest,
            List<String> queryTokens
    ) {
        if (queryTokens.isEmpty()) {
            return true;
        }
        if (structure == null || chest == null) {
            return false;
        }
        Set<String> chestTokens = new LinkedHashSet<>();
        addSearchTokens(chestTokens, structure.id);
        addSearchTokens(chestTokens, structure.type);
        addSearchTokens(chestTokens, structure.dimension);
        addSearchTokens(chestTokens, chest.blockId);
        addSearchTokens(chestTokens, chest.lootTable);
        if (chest.items != null) {
            for (LootSampler.ItemStackData item : chest.items) {
                if (item == null) {
                    continue;
                }
                addSearchTokens(chestTokens, item.itemId);
                addSearchTokens(chestTokens, item.displayName);
                addSearchTokens(chestTokens, formatItemLabel(item));
                addSearchTokens(chestTokens, parseEnchantText(item.nbt != null ? item.nbt : ""));
                addSearchTokens(chestTokens, parsePotionText(item.nbt != null ? item.nbt : ""));
            }
        }
        for (String queryToken : queryTokens) {
            if (!tokenPresent(chestTokens, queryToken)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> parseQueryTokens(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Matcher m = SEARCH_TOKEN_PATTERN.matcher(raw.toLowerCase());
        while (m.find()) {
            String token = m.group();
            addSearchToken(out, token);
        }
        return new ArrayList<>(out);
    }

    private static void addSearchTokens(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Matcher m = SEARCH_TOKEN_PATTERN.matcher(raw.toLowerCase());
        while (m.find()) {
            addSearchToken(out, m.group());
        }
    }

    private static void addSearchToken(Set<String> out, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        out.add(token);
        try {
            int parsed = Integer.parseInt(token);
            if (parsed > 0 && parsed <= 50) {
                out.add(toRoman(parsed).toLowerCase());
            }
            return;
        } catch (NumberFormatException ignored) {
        }
        int roman = parseRoman(token);
        if (roman > 0 && roman <= 50) {
            out.add(Integer.toString(roman));
        }
    }

    private static boolean tokenPresent(Set<String> haystack, String queryToken) {
        if (haystack.contains(queryToken)) {
            return true;
        }
        if (queryToken.length() < 3) {
            return false;
        }
        for (String candidate : haystack) {
            if (candidate.contains(queryToken)) {
                return true;
            }
        }
        return false;
    }

    private static int parseRoman(String token) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        int total = 0;
        int prev = 0;
        for (int i = token.length() - 1; i >= 0; i--) {
            int value = romanValue(token.charAt(i));
            if (value <= 0) {
                return -1;
            }
            if (value < prev) {
                total -= value;
            } else {
                total += value;
                prev = value;
            }
        }
        return total;
    }

    private static int romanValue(char c) {
        return switch (c) {
            case 'i' -> 1;
            case 'v' -> 5;
            case 'x' -> 10;
            case 'l' -> 50;
            case 'c' -> 100;
            case 'd' -> 500;
            case 'm' -> 1000;
            default -> -1;
        };
    }

    private void loadResultJson() {
        JFileChooser chooser = newFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Path path = chooser.getSelectedFile().toPath().toAbsolutePath();
            ProbeResult loaded = mapper.readValue(path.toFile(), ProbeResult.class);
            if (loaded.regionScan != null) {
                mapPanel.setResult(loaded.regionScan.seed, loaded);
            } else {
                mapPanel.setResult(loaded.seed, loaded);
            }
            setCurrentResult(loaded);
            log(appLog, "Loaded result JSON: " + path);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Load failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportSimplifiedResults() {
        if (currentResult == null || currentResult.regionScan == null || currentResult.regionScan.structures == null) {
            JOptionPane.showMessageDialog(frame, "No scan result available to export.", "Export Simplified", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser chooser = newFileChooser();
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        chooser.setSelectedFile(new File("simplified-results.json"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {
            Path out = chooser.getSelectedFile().toPath().toAbsolutePath();
            SimplifiedResults export = new SimplifiedResults();
            export.seed = currentResult.seed;
            export.dimension = currentResult.regionScan.dimension;
            export.center_x = currentResult.regionScan.centerX;
            export.center_z = currentResult.regionScan.centerZ;
            export.radius = currentResult.regionScan.radius;
            export.minecraftVersion = currentResult.mcVersion;

            List<WorldChestScanner.ScannedStructure> scanned = currentResult.regionScan.structures;
            for (int structureIndex = 0; structureIndex < scanned.size(); structureIndex++) {
                WorldChestScanner.ScannedStructure structure = scanned.get(structureIndex);
                if (structure == null || structure.chests == null || structure.chests.isEmpty()) {
                    continue;
                }
                String type = (structure.type != null && !structure.type.isBlank())
                        ? structure.type
                        : shortId(structure.id);
                for (int chestIndex = 0; chestIndex < structure.chests.size(); chestIndex++) {
                    WorldChestScanner.ChestData chest = structure.chests.get(chestIndex);
                    SimplifiedStructure entry = new SimplifiedStructure();
                    entry.id = type + "-" + structureIndex + "-" + chestIndex;
                    entry.type = type;
                    entry.x = chest.x;
                    entry.y = chest.y;
                    entry.z = chest.z;
                    if (chest.items != null) {
                        for (LootSampler.ItemStackData item : chest.items) {
                            SimplifiedItem outItem = new SimplifiedItem();
                            outItem.slot = item.slot;
                            outItem.count = item.count;
                            outItem.itemId = item.itemId;
                            outItem.id = item.id != null ? item.id : item.itemId;
                            outItem.displayName = (item.displayName != null && !item.displayName.isBlank())
                                    ? item.displayName
                                    : titleFromSnake(shortId(item.itemId));
                            outItem.nbt = item.nbt;
                            if (item.enchantments != null) {
                                outItem.enchantments.addAll(item.enchantments);
                            }
                            if (item.enchantmentLevels != null) {
                                outItem.enchantmentLevels.addAll(item.enchantmentLevels);
                            }
                            entry.items.add(outItem);
                        }
                    }
                    export.structures.add(entry);
                }
            }

            mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), export);
            log(appLog, "Exported simplified results: " + out);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSettingsQuietly() {
        try {
            GuiSettings s = new GuiSettings();
            s.mcVersion = mcVersion.getText();
            s.seed = seed.getText();
            s.serverJar = serverJar.getText();
            s.output = output.getText();
            s.workDir = workDir.getText();
            s.startupTimeout = startupTimeout.getText();
            s.dimension = String.valueOf(dimensionDropdown.getSelectedItem());
            s.scanCenterX = scanCenterX.getText();
            s.scanCenterZ = scanCenterZ.getText();
            s.scanRadius = scanRadius.getText();
            s.locateStep = locateStep.getText();
            s.extractChunkRadius = extractChunkRadius.getText();
            s.extractParallelChunks = extractParallelChunks.isSelected();
            s.extractParallelChunkCount = String.valueOf(extractParallelChunkCount.getValue());
            s.extractParallelStructures = String.valueOf(extractParallelStructures.getValue());
            s.extractTimeout = extractTimeout.getText();
            s.extractStartTimeoutMs = extractStartTimeoutMs.getText();
            s.extractStatusTimeoutMs = extractStatusTimeoutMs.getText();
            s.maxStructures = maxStructures.getText();
            s.pluginJar = pluginJar.getText();
            s.cubiomesMap = cubiomesMap.isSelected();
            s.cubiomesStructures = cubiomesStructures.isSelected();
            s.mapMaxZoomOut = mapMaxZoomOut.getText();
            s.mapIconScale = mapIconScale.getText();
            s.datapack = datapackPath.getText();
            s.autoDatapackStructures = autoDatapackStructures.isSelected();
            s.ultraLean = ultraLean.isSelected();
            s.selectedStructures = selectedStructures.getText();
            s.datapacks = new ArrayList<>();
            if (s.datapack != null && !s.datapack.isBlank()) {
                s.datapacks.add(s.datapack);
            }
            if (settingsFile.getParent() != null) {
                Files.createDirectories(settingsFile.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), s);
        } catch (Exception ignored) {
        }
    }

    private void loadSettingsQuietly() {
        try {
            if (!Files.exists(settingsFile)) {
                return;
            }
            GuiSettings s = mapper.readValue(settingsFile.toFile(), GuiSettings.class);
            if (s == null) {
                return;
            }
            if (s.mcVersion != null) mcVersion.setText(s.mcVersion);
            if (s.seed != null) seed.setText(s.seed);
            if (s.serverJar != null) serverJar.setText(s.serverJar);
            if (s.output != null) output.setText(s.output);
            if (s.workDir != null) workDir.setText(s.workDir);
            if (s.startupTimeout != null) startupTimeout.setText(s.startupTimeout);
            String dimension = s.dimension != null ? s.dimension : (s.scanDimension != null ? s.scanDimension : s.structureDimension);
            if (dimension != null && !dimension.isBlank()) {
                dimensionDropdown.setSelectedItem(dimension);
            }
            if (s.scanCenterX != null) scanCenterX.setText(s.scanCenterX);
            if (s.scanCenterZ != null) scanCenterZ.setText(s.scanCenterZ);
            if (s.scanRadius != null) scanRadius.setText(s.scanRadius);
            if (s.locateStep != null) locateStep.setText(s.locateStep);
            if (s.extractChunkRadius != null) extractChunkRadius.setText(s.extractChunkRadius);
            extractParallelChunks.setSelected(s.extractParallelChunks);
            if (s.extractParallelChunkCount != null) extractParallelChunkCount.setValue(parseSliderSetting(s.extractParallelChunkCount, extractParallelChunkCount));
            if (s.extractParallelStructures != null) extractParallelStructures.setValue(parseSliderSetting(s.extractParallelStructures, extractParallelStructures));
            updateParallelControlsEnabled();
            if (s.extractTimeout != null) extractTimeout.setText(s.extractTimeout);
            if (s.extractStartTimeoutMs != null) extractStartTimeoutMs.setText(s.extractStartTimeoutMs);
            if (s.extractStatusTimeoutMs != null) extractStatusTimeoutMs.setText(s.extractStatusTimeoutMs);
            if (s.maxStructures != null) maxStructures.setText(s.maxStructures);
            if (s.pluginJar != null) pluginJar.setText(s.pluginJar);
            if (s.cubiomesMap != null) cubiomesMap.setSelected(s.cubiomesMap);
            if (s.cubiomesStructures != null) cubiomesStructures.setSelected(s.cubiomesStructures);
            if (s.mapMaxZoomOut != null) mapMaxZoomOut.setText(s.mapMaxZoomOut);
            if (s.mapIconScale != null) mapIconScale.setText(s.mapIconScale);
            if (s.datapack != null && !s.datapack.isBlank()) {
                setDatapackPath(Path.of(s.datapack));
            } else if (s.datapacks != null && !s.datapacks.isEmpty() && s.datapacks.get(0) != null && !s.datapacks.get(0).isBlank()) {
                setDatapackPath(Path.of(s.datapacks.get(0)));
            } else {
                setDatapackPath(null);
            }
            autoDatapackStructures.setSelected(s.autoDatapackStructures);
            ultraLean.setSelected(s.ultraLean);
            if (s.selectedStructures != null && !s.selectedStructures.isBlank()) {
                selectedStructures.setText(s.selectedStructures);
            }
            rebuildTargetSelectorsFromText();
            syncMapPreviewOptions();
        } catch (Exception ignored) {
        }
    }

    private static void addFormRow(JPanel form, String label, java.awt.Component input) {
        form.add(new JLabel(label));
        form.add(input);
    }

    private static void addScanRow(JPanel form, int row, String label, java.awt.Component input) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.weightx = 0.0;
        left.fill = GridBagConstraints.NONE;
        left.anchor = GridBagConstraints.LINE_START;
        left.insets = new Insets(0, 0, 6, 10);
        form.add(new JLabel(label), left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1.0;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.LINE_START;
        right.insets = new Insets(0, 0, 6, 0);
        form.add(input, right);
    }

    private static void appendLine(JTextArea area, String value) {
        String current = area.getText().trim();
        if (current.isEmpty()) {
            area.setText(value);
            return;
        }
        for (String s : parseLines(current)) {
            if (s.equals(value)) {
                return;
            }
        }
        area.append("\n" + value);
    }

    private static String requireText(JTextField field, String name) {
        String v = field.getText() != null ? field.getText().trim() : "";
        if (v.isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return v;
    }

    private static void chooseFile(JTextField target, boolean dirsOnly, String extension) {
        JFileChooser chooser = newFileChooser();
        chooser.setFileSelectionMode(dirsOnly ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (!dirsOnly && extension != null) {
            chooser.setFileFilter(new FileNameExtensionFilter("*." + extension, extension));
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
        }
    }

    private static void chooseSaveFile(JTextField target, String extension) {
        JFileChooser chooser = newFileChooser();
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        if (extension != null) {
            chooser.setFileFilter(new FileNameExtensionFilter("*." + extension, extension));
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
        }
    }

    private static JFileChooser newFileChooser() {
        JFileChooser chooser = new JFileChooser();
        File cwd = Path.of("").toAbsolutePath().toFile();
        chooser.setCurrentDirectory(cwd);
        return chooser;
    }

    private static List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[,\\n\\r\\t ]+")) {
            String s = raw.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<ProbeConfig.StructureTarget> parseStructureTargets(String text, String fallbackDimension) {
        List<ProbeConfig.StructureTarget> out = new ArrayList<>();
        for (String token : parseLines(text)) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            int sep = value.indexOf('|');
            if (sep > 0 && sep + 1 < value.length()) {
                String dim = value.substring(0, sep).trim();
                String id = value.substring(sep + 1).trim();
                if (!id.isEmpty()) {
                    out.add(new ProbeConfig.StructureTarget(id, dim.isEmpty() ? fallbackDimension : dim));
                }
                continue;
            }
            out.add(new ProbeConfig.StructureTarget(value, fallbackDimension));
        }
        return out;
    }

    private static String formatTargetLine(String structureId, String dimension) {
        String dim = (dimension != null && !dimension.isBlank()) ? dimension : "minecraft:overworld";
        return dim + "|" + structureId.trim();
    }

    private static final class DatapackTargetRow {
        final String id;
        final JPanel panel;
        final JCheckBox enabled;
        final JComboBox<String> dimension;

        DatapackTargetRow(String id) {
            this.id = id;
            this.panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            this.enabled = new JCheckBox(id, false);
            this.dimension = new JComboBox<>(new String[]{
                    "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
            });
            this.dimension.setSelectedItem("minecraft:overworld");
            panel.add(enabled);
            panel.add(new JLabel("Dimension"));
            panel.add(dimension);
        }
    }

    private static Integer blankToNullInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }

    private static Integer parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parseDoubleSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseSliderSetting(String value, JSlider slider) {
        try {
            int v = Integer.parseInt(value);
            return Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), v));
        } catch (Exception ignored) {
            return slider.getValue();
        }
    }

    private static Path blankToNullPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim());
    }

    private void updateParallelControlsEnabled() {
        boolean enabled = extractParallelChunks.isSelected();
        extractParallelChunkCount.setEnabled(enabled);
        extractParallelStructures.setEnabled(enabled);
    }

    private static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setPaintTicks(true);
        s.setPaintLabels(true);
        s.setMajorTickSpacing(1);
        s.setMinorTickSpacing(1);
        return s;
    }

    private List<Path> activeDatapacks() {
        Path p = blankToNullPath(datapackPath.getText());
        if (p == null) {
            return List.of();
        }
        return List.of(p.toAbsolutePath());
    }

    private void setDatapackPath(Path path) {
        datapackPath.setText(path == null ? "" : path.toAbsolutePath().toString());
    }

    private static void log(JTextArea area, String line) {
        area.append(line + "\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    private interface MapInteractionListener {
        void onScanCircleChanged(int centerX, int centerZ, int radius);
    }

    private static final class MapPreviewPanel extends JPanel {
        private long seed;
        private ProbeResult result;
        private final CubiomesBridge cubiomesBridge;
        private WorldChestScanner.ScannedStructure selectedStructure;
        private WorldChestScanner.ChestData selectedChest;
        private boolean useCubiomesMap;
        private boolean useCubiomesStructures;
        private int maxZoomOutBlocks = 15000;
        private double iconScale = 1.0;
        private String mcVersion = "1.21";
        private String dimension = "minecraft:overworld";
        private List<String> selectedStructureIds = List.of();
        private List<CubiomesBridge.StructurePoint> cachedCubiomesStructures = List.of();
        private String cachedCubiomesStructureKey;
        private final Map<String, List<CubiomesBridge.StructurePoint>> cubiomesStructureCache = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<CubiomesBridge.StructurePoint>> eldest) {
                return size() > 64;
            }
        };
        private final Map<Integer, BufferedImage> structureIconCache = new HashMap<>();
        private Integer scanCenterX;
        private Integer scanCenterZ;
        private Integer scanRadius;
        private final MapInteractionListener mapInteractionListener;
        private boolean draggingScanRadius;
        private boolean draggingPan;
        private int dragCenterX;
        private int dragCenterZ;
        private int panOffsetX;
        private int panOffsetZ;
        private int panStartMouseX;
        private int panStartMouseY;
        private int panStartOffsetX;
        private int panStartOffsetZ;
        private double panStartRadius;
        private double zoomFactor = 1.0;
        private Integer hoverWorldX;
        private Integer hoverWorldZ;
        private BufferedImage cached;
        private int cachedW;
        private int cachedH;
        private int cachedCenterX;
        private int cachedCenterZ;
        private double cachedRadius;
        private String cachedImageBaseKey;
        private String cachedImageKey;
        private final Map<String, RenderFrame> mapFrameCache = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RenderFrame> eldest) {
                return size() > 32;
            }
        };
        private volatile boolean imageRenderInFlight;
        private volatile String requestedImageKey;
        private volatile boolean structureGenInFlight;
        private volatile String requestedStructureKey;
        private long lastHoverPaintNanos;
        private final Timer interactionDebounceTimer;
        private final Timer interactionFrameTimer;

        MapPreviewPanel(MapInteractionListener mapInteractionListener, CubiomesBridge cubiomesBridge) {
            this.mapInteractionListener = mapInteractionListener;
            this.cubiomesBridge = cubiomesBridge;
            setPreferredSize(new Dimension(600, 500));
            setBackground(Color.BLACK);
            ToolTipManager.sharedInstance().unregisterComponent(this);
            interactionDebounceTimer = new Timer(120, e -> repaint());
            interactionDebounceTimer.setRepeats(false);
            interactionFrameTimer = new Timer(16, e -> repaint());
            interactionFrameTimer.setRepeats(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                        draggingPan = true;
                        panStartMouseX = e.getX();
                        panStartMouseY = e.getY();
                        panStartOffsetX = panOffsetX;
                        panStartOffsetZ = panOffsetZ;
                        panStartRadius = currentViewport(getWidth(), getHeight()).radius;
                        return;
                    }
                    if (!SwingUtilities.isRightMouseButton(e)) {
                        return;
                    }
                    Viewport viewport = currentViewport(getWidth(), getHeight());
                    int worldX = toWorld(e.getX(), viewport.centerX, viewport.radius, getWidth());
                    int worldZ = toWorld(e.getY(), viewport.centerZ, viewport.radius, getHeight());
                    dragCenterX = worldX;
                    dragCenterZ = worldZ;
                    draggingScanRadius = true;
                    setScanCircle(worldX, worldZ, 1);
                    if (mapInteractionListener != null) {
                        mapInteractionListener.onScanCircleChanged(worldX, worldZ, 1);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (draggingPan) {
                        updateHoverWorld(e.getX(), e.getY());
                        int w = Math.max(getWidth(), 1);
                        int h = Math.max(getHeight(), 1);
                        double worldPerPixelX = (2.0 * panStartRadius) / w;
                        double worldPerPixelZ = (2.0 * panStartRadius) / h;
                        int dx = e.getX() - panStartMouseX;
                        int dz = e.getY() - panStartMouseY;
                        panOffsetX = panStartOffsetX - (int) Math.round(dx * worldPerPixelX);
                        panOffsetZ = panStartOffsetZ - (int) Math.round(dz * worldPerPixelZ);
                        markViewportChanged();
                        return;
                    }
                    if (!draggingScanRadius) {
                        updateHoverWorld(e.getX(), e.getY());
                        return;
                    }
                    updateHoverWorld(e.getX(), e.getY());
                    Viewport viewport = currentViewport(getWidth(), getHeight());
                    int worldX = toWorld(e.getX(), viewport.centerX, viewport.radius, getWidth());
                    int worldZ = toWorld(e.getY(), viewport.centerZ, viewport.radius, getHeight());
                    int radius = (int) Math.max(1, Math.round(Math.hypot(worldX - dragCenterX, worldZ - dragCenterZ)));
                    setScanCircle(dragCenterX, dragCenterZ, radius);
                    if (mapInteractionListener != null) {
                        mapInteractionListener.onScanCircleChanged(dragCenterX, dragCenterZ, radius);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (draggingPan && (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e))) {
                        draggingPan = false;
                        return;
                    }
                    if (!draggingScanRadius || !SwingUtilities.isRightMouseButton(e)) {
                        return;
                    }
                    draggingScanRadius = false;
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    updateHoverWorld(e.getX(), e.getY());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverWorldX = null;
                    hoverWorldZ = null;
                    repaint();
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    Viewport before = currentViewport(getWidth(), getHeight());
                    int anchorWorldX = toWorld(e.getX(), before.centerX, before.radius, Math.max(getWidth(), 1));
                    int anchorWorldZ = toWorld(e.getY(), before.centerZ, before.radius, Math.max(getHeight(), 1));
                    int rotation = e.getWheelRotation();
                    if (rotation < 0) {
                        zoomFactor *= 1.15;
                    } else if (rotation > 0) {
                        zoomFactor /= 1.15;
                    }
                    zoomFactor = Math.max(0.25, Math.min(30.0, zoomFactor));
                    Viewport after = currentViewport(getWidth(), getHeight());
                    int anchorAfterX = toWorld(e.getX(), after.centerX, after.radius, Math.max(getWidth(), 1));
                    int anchorAfterZ = toWorld(e.getY(), after.centerZ, after.radius, Math.max(getHeight(), 1));
                    panOffsetX += (anchorWorldX - anchorAfterX);
                    panOffsetZ += (anchorWorldZ - anchorAfterZ);
                    markViewportChanged();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            return null;
        }

        void setResult(long seed, ProbeResult result) {
            this.seed = seed;
            this.result = result;
            this.selectedStructure = null;
            this.selectedChest = null;
            this.panOffsetX = 0;
            this.panOffsetZ = 0;
            this.cached = null;
            this.cachedImageKey = null;
            this.cachedCubiomesStructureKey = null;
            this.cachedCubiomesStructures = List.of();
            this.mapFrameCache.clear();
            this.cubiomesStructureCache.clear();
            this.requestedImageKey = null;
            this.requestedStructureKey = null;
            repaint();
        }

        void setSelection(WorldChestScanner.ScannedStructure structure, WorldChestScanner.ChestData chest) {
            this.selectedStructure = structure;
            this.selectedChest = chest;
            repaint();
        }

        void setScanCircle(Integer centerX, Integer centerZ, Integer radius) {
            this.scanCenterX = centerX;
            this.scanCenterZ = centerZ;
            this.scanRadius = radius != null ? Math.max(1, radius) : null;
            this.panOffsetX = 0;
            this.panOffsetZ = 0;
            repaint();
        }

        void setCubiomesOptions(
                boolean useCubiomesMap,
                boolean useCubiomesStructures,
                int maxZoomOutBlocks,
                double iconScale,
                String mcVersion,
                String dimension,
                List<String> selectedStructureIds
        ) {
            this.useCubiomesMap = useCubiomesMap;
            this.useCubiomesStructures = useCubiomesStructures;
            this.maxZoomOutBlocks = Math.max(2000, maxZoomOutBlocks);
            this.iconScale = Math.max(0.2, Math.min(6.0, iconScale));
            this.mcVersion = mcVersion != null ? mcVersion : "1.21";
            this.dimension = dimension != null ? dimension : "minecraft:overworld";
            this.selectedStructureIds = selectedStructureIds != null ? new ArrayList<>(selectedStructureIds) : List.of();
            this.cached = null;
            this.cachedImageKey = null;
            this.cachedCubiomesStructureKey = null;
            this.cachedCubiomesStructures = List.of();
            this.requestedImageKey = null;
            this.requestedStructureKey = null;
            this.mapFrameCache.clear();
            this.cubiomesStructureCache.clear();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            if (w <= 2 || h <= 2) {
                return;
            }
            Viewport viewport = currentViewport(w, h);
            boolean interacting = isInteracting();
            if (!interacting || cached == null) {
                ensureImage(w, h, viewport);
            }
            Graphics2D g2 = (Graphics2D) g;
            if (interacting) {
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            } else {
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            drawCachedImage(g2, w, h, viewport);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int centerX = w / 2;
            int centerY = h / 2;
            g2.setColor(new Color(255, 255, 255, 40));
            g2.drawLine(centerX, 0, centerX, h);
            g2.drawLine(0, centerY, w, centerY);

            double radius = viewport.radius;
            int worldCenterX = viewport.centerX;
            int worldCenterZ = viewport.centerZ;
            List<String> topHud = new ArrayList<>();
            List<String> bottomHud = new ArrayList<>();
            int renderedStructures = 0;
            int renderedChests = 0;

            if (result != null && result.regionScan != null && result.regionScan.structures != null) {
                topHud.add("Scanned structures: " + result.regionScan.structures.size());
                if (selectedStructure != null) {
                    String selectedText = "Selected structure: " + (selectedStructure.id != null ? selectedStructure.id : "-")
                            + " @ (" + selectedStructure.x + "," + selectedStructure.z + ")";
                    topHud.add(selectedText);
                }
                if (selectedChest != null) {
                    String chestText = "Selected chest: (" + selectedChest.x + "," + selectedChest.y + "," + selectedChest.z + ")";
                    topHud.add(chestText);
                }
            }

            if (scanCenterX != null && scanCenterZ != null && scanRadius != null) {
                int cx = toPixel(scanCenterX, worldCenterX, radius, w);
                int cz = toPixel(scanCenterZ, worldCenterZ, radius, h);
                int rx = (int) Math.max(1, Math.round((scanRadius / radius) * (w / 2.0)));
                int rz = (int) Math.max(1, Math.round((scanRadius / radius) * (h / 2.0)));
                g2.setColor(new Color(89, 255, 155, 55));
                g2.fillOval(cx - rx, cz - rz, rx * 2, rz * 2);
                g2.setColor(new Color(89, 255, 155, 220));
                g2.drawOval(cx - rx, cz - rz, rx * 2, rz * 2);
                g2.drawLine(cx - 8, cz, cx + 8, cz);
                g2.drawLine(cx, cz - 8, cx, cz + 8);
                bottomHud.add("Scan center: (" + scanCenterX + "," + scanCenterZ + ")  radius=" + scanRadius);
            }

            if (!interacting) {
                drawCubiomesStructures(g2, viewport, w, h);
                renderedStructures = drawScannedStructurePoints(g2, viewport, w, h);
            }
            renderedChests = drawScannedChestPoints(g2, viewport, w, h, interacting ? 1400 : 9000);
            if (result != null && result.regionScan != null && result.regionScan.structures != null) {
                topHud.add("Visible markers: structures=" + renderedStructures + ", chests=" + renderedChests);
            }

            if (selectedStructure != null) {
                int px = toPixel(selectedStructure.x, worldCenterX, radius, w);
                int py = toPixel(selectedStructure.z, worldCenterZ, radius, h);
                int halo = Math.max(8, (int) Math.round(16 * iconScale));
                int core = Math.max(5, (int) Math.round(7 * iconScale));
                int ring = Math.max(6, (int) Math.round(8 * iconScale));
                int cross = Math.max(8, (int) Math.round(12 * iconScale));
                g2.setColor(new Color(68, 220, 255, 70));
                g2.fillOval(px - halo, py - halo, halo * 2, halo * 2);
                g2.setColor(new Color(68, 220, 255, 240));
                g2.fillOval(px - core, py - core, core * 2, core * 2);
                g2.setColor(new Color(255, 255, 255, 220));
                g2.drawOval(px - ring, py - ring, ring * 2, ring * 2);
                g2.drawLine(px - cross, py, px + cross, py);
                g2.drawLine(px, py - cross, px, py + cross);
            }
            if (selectedChest != null) {
                int px = toPixel(selectedChest.x, worldCenterX, radius, w);
                int py = toPixel(selectedChest.z, worldCenterZ, radius, h);
                int chest = Math.max(5, (int) Math.round(6 * iconScale));
                int chestRing = chest + 1;
                g2.setColor(new Color(255, 222, 89, 250));
                g2.fillOval(px - chest, py - chest, chest * 2, chest * 2);
                g2.setColor(new Color(18, 18, 18, 230));
                g2.drawOval(px - chestRing, py - chestRing, chestRing * 2, chestRing * 2);
            }

            bottomHud.add("Seed: " + seed);
            bottomHud.add(String.format("Zoom: %.2fx", zoomFactor));
            if (hoverWorldX != null && hoverWorldZ != null) {
                bottomHud.add("Mouse: (" + hoverWorldX + "," + hoverWorldZ + ")");
            }
            if (interacting) {
                bottomHud.add("Rendering: interactive mode");
            }

            drawHudBlock(g2, topHud, 10, 10, w - 20, false, 11f, false);
            drawHudBlock(g2, bottomHud, 10, h - 10, w - 20, true, 13f, true);
        }

        private int toPixel(int world, int center, double radius, int screen) {
            double normalized = (world - center) / radius;
            return (int) Math.round((normalized + 1.0) * 0.5 * screen);
        }

        private int toWorld(int pixel, int center, double radius, int screen) {
            if (screen <= 0) {
                return center;
            }
            double normalized = (pixel / (double) screen) * 2.0 - 1.0;
            return (int) Math.round(center + normalized * radius);
        }

        private Viewport currentViewport(int w, int h) {
            double baseRadius = 6000;
            int centerX = 0;
            int centerZ = 0;
            if (result != null && result.regionScan != null) {
                baseRadius = Math.max(baseRadius, Math.max(200, result.regionScan.radius));
                centerX = result.regionScan.centerX;
                centerZ = result.regionScan.centerZ;
            }
            if (scanRadius != null && scanRadius > 0) {
                baseRadius = Math.max(baseRadius, scanRadius * 1.2);
            }
            if (scanCenterX != null && scanCenterZ != null) {
                centerX = scanCenterX;
                centerZ = scanCenterZ;
            }
            centerX += panOffsetX;
            centerZ += panOffsetZ;
            double effectiveRadius = Math.max(40.0, baseRadius / Math.max(0.25, zoomFactor));
            double maxRadius = Math.max(500.0, maxZoomOutBlocks / 2.0);
            effectiveRadius = Math.min(effectiveRadius, maxRadius);
            return new Viewport(centerX, centerZ, effectiveRadius);
        }

        private void updateHoverWorld(int mouseX, int mouseY) {
            Viewport viewport = currentViewport(getWidth(), getHeight());
            hoverWorldX = toWorld(mouseX, viewport.centerX, viewport.radius, Math.max(getWidth(), 1));
            hoverWorldZ = toWorld(mouseY, viewport.centerZ, viewport.radius, Math.max(getHeight(), 1));
            if (draggingPan || draggingScanRadius) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastHoverPaintNanos > 33_000_000L) {
                lastHoverPaintNanos = now;
                repaint();
            }
        }

        private void drawHudBlock(
                Graphics2D g2,
                List<String> lines,
                int x,
                int y,
                int maxWidth,
                boolean anchorBottom,
                float fontSize,
                boolean strongBackground
        ) {
            if (lines == null || lines.isEmpty()) {
                return;
            }
            Font old = g2.getFont();
            Font font = old.deriveFont(Font.BOLD, fontSize);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            List<String> clipped = new ArrayList<>(lines.size());
            int textWidth = 0;
            for (String line : lines) {
                String safe = line != null ? line : "";
                String fit = clipToWidth(safe, fm, Math.max(80, maxWidth - 24));
                clipped.add(fit);
                textWidth = Math.max(textWidth, fm.stringWidth(fit));
            }

            int padding = 2;
            int lineGap = 1;
            int contentHeight = clipped.size() * fm.getHeight() + Math.max(0, clipped.size() - 1) * lineGap;
            int boxW = Math.min(maxWidth, textWidth + padding * 2);
            int boxH = contentHeight + padding * 2;
            int boxX = x;
            int boxY = anchorBottom ? y - boxH : y;

            g2.setColor(strongBackground ? new Color(0, 0, 0, 140) : new Color(0, 0, 0, 95));
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
            g2.setColor(new Color(255, 255, 255, 245));

            int ty = boxY + padding + fm.getAscent();
            for (String line : clipped) {
                g2.drawString(line, boxX + padding, ty);
                ty += fm.getHeight() + lineGap;
            }
            g2.setFont(old);
        }

        private static String clipToWidth(String text, FontMetrics fm, int maxWidth) {
            if (text == null) {
                return "";
            }
            if (fm.stringWidth(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            int end = text.length();
            while (end > 1) {
                String candidate = text.substring(0, end) + ellipsis;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    return candidate;
                }
                end--;
            }
            return ellipsis;
        }

        private record Viewport(int centerX, int centerZ, double radius) {
        }

        private int drawScannedStructurePoints(Graphics2D g2, Viewport viewport, int w, int h) {
            if (result == null || result.regionScan == null || result.regionScan.structures == null || result.regionScan.structures.isEmpty()) {
                return 0;
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setColor(new Color(255, 56, 56, 230));
            int half = Math.max(1, (int) Math.round(2 * iconScale));
            int size = half * 2;
            int drawn = 0;
            int maxDraw = 1600;
            for (WorldChestScanner.ScannedStructure s : result.regionScan.structures) {
                int px = toPixel(s.x, viewport.centerX, viewport.radius, w);
                int py = toPixel(s.z, viewport.centerZ, viewport.radius, h);
                if (px < -3 || py < -3 || px > w + 3 || py > h + 3) {
                    continue;
                }
                g2.fillRect(px - half, py - half, size, size);
                drawn++;
                if (drawn >= maxDraw) {
                    break;
                }
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            return drawn;
        }

        private int drawScannedChestPoints(Graphics2D g2, Viewport viewport, int w, int h, int maxDraw) {
            if (result == null || result.regionScan == null || result.regionScan.structures == null || result.regionScan.structures.isEmpty()) {
                return 0;
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setColor(new Color(255, 0, 190, 245));
            int half = Math.max(2, (int) Math.round(3 * iconScale));
            int size = half * 2 + 1;
            int drawn = 0;
            for (WorldChestScanner.ScannedStructure s : result.regionScan.structures) {
                if (s == null || s.chests == null || s.chests.isEmpty()) {
                    continue;
                }
                for (WorldChestScanner.ChestData chest : s.chests) {
                    if (chest == null) {
                        continue;
                    }
                    int px = toPixel(chest.x, viewport.centerX, viewport.radius, w);
                    int py = toPixel(chest.z, viewport.centerZ, viewport.radius, h);
                    if (px < -2 || py < -2 || px > w + 2 || py > h + 2) {
                        continue;
                    }
                    g2.fillRect(px - half, py - half, size, size);
                    g2.setColor(new Color(20, 20, 20, 220));
                    g2.drawRect(px - half - 1, py - half - 1, size + 1, size + 1);
                    g2.setColor(new Color(255, 0, 190, 245));
                    drawn++;
                    if (drawn >= maxDraw) {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        return drawn;
                    }
                }
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            return drawn;
        }

        private void ensureImage(int w, int h, Viewport viewport) {
            String baseKey = seed + "|" + w + "x" + h + "|" + useCubiomesMap + "|" + mcVersion + "|" + dimension
                    + "|" + DEFAULT_CUBIOMES_DLL + "|" + DEFAULT_CUBIOMES_BRIDGE;
            int centerSnap = Math.max(128, (int) Math.round(viewport.radius / 8.0));
            int radiusSnap = Math.max(64, (int) Math.round(viewport.radius / 10.0));
            int snappedCenterX = floorTo(viewport.centerX, centerSnap);
            int snappedCenterZ = floorTo(viewport.centerZ, centerSnap);
            int snappedRadius = Math.max(40, ceilTo((int) Math.round(viewport.radius), radiusSnap));
            String key = baseKey + "|" + snappedCenterX + "|" + snappedCenterZ + "|" + snappedRadius;
            if (cached != null && key.equals(cachedImageKey)) {
                return;
            }
            RenderFrame frame = mapFrameCache.get(key);
            if (frame != null) {
                cached = frame.image();
                cachedW = frame.width();
                cachedH = frame.height();
                cachedCenterX = frame.centerX();
                cachedCenterZ = frame.centerZ();
                cachedRadius = frame.radius();
                cachedImageBaseKey = frame.baseKey();
                cachedImageKey = key;
                return;
            }
            if (cached != null && baseKey.equals(cachedImageBaseKey) && cachedW == w && cachedH == h) {
                double dx = Math.abs(viewport.centerX - cachedCenterX);
                double dz = Math.abs(viewport.centerZ - cachedCenterZ);
                double ratio = viewport.radius / Math.max(1.0, cachedRadius);
                if (dx < viewport.radius * 0.35 && dz < viewport.radius * 0.35 && ratio > 0.72 && ratio < 1.38) {
                    return;
                }
            }
            if (interactionDebounceTimer.isRunning()) {
                requestedImageKey = key;
                return;
            }
            requestedImageKey = key;
            if (imageRenderInFlight) {
                return;
            }
            imageRenderInFlight = true;
            final String renderKey = key;
            final String renderBaseKey = baseKey;
            final int rw = w;
            final int rh = h;
            final int rcx = snappedCenterX;
            final int rcz = snappedCenterZ;
            final int rr = snappedRadius;
            SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
                @Override
                protected BufferedImage doInBackground() {
                    BufferedImage img = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_ARGB);
                    boolean rendered = false;
                    if (useCubiomesMap && cubiomesBridge.ensureInitialized(DEFAULT_CUBIOMES_BRIDGE, DEFAULT_CUBIOMES_DLL)) {
                        int[] argb = cubiomesBridge.renderMap(seed, mcVersion, dimension, rcx, rcz, rr, rw, rh);
                        if (argb != null && argb.length == rw * rh) {
                            img.setRGB(0, 0, rw, rh, argb, 0, rw);
                            rendered = true;
                        }
                    }
                    if (!rendered) {
                        for (int y = 0; y < rh; y++) {
                            for (int x = 0; x < rw; x++) {
                                int worldX = (x - rw / 2) * 24;
                                int worldZ = (y - rh / 2) * 24;
                                long n = hash(seed, worldX, worldZ);
                                int t = (int) (Math.abs(n) & 255);
                                img.setRGB(x, y, colorFor(t).getRGB());
                            }
                        }
                    }
                    return img;
                }

                @Override
                protected void done() {
                    try {
                        BufferedImage img = get();
                        if (renderKey.equals(requestedImageKey)) {
                            cached = img;
                            cachedW = rw;
                            cachedH = rh;
                            cachedCenterX = rcx;
                            cachedCenterZ = rcz;
                            cachedRadius = rr;
                            cachedImageBaseKey = renderBaseKey;
                            cachedImageKey = renderKey;
                            mapFrameCache.put(renderKey, new RenderFrame(img, rw, rh, rcx, rcz, rr, renderBaseKey));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        imageRenderInFlight = false;
                        repaint();
                    }
                }
            };
            worker.execute();
        }

        private void drawCubiomesStructures(Graphics2D g2, Viewport viewport, int w, int h) {
            if (!useCubiomesStructures || selectedStructureIds == null || selectedStructureIds.isEmpty()) {
                return;
            }
            if (isInteracting()) {
                return;
            }
            int minX = (int) Math.round(viewport.centerX - viewport.radius);
            int maxX = (int) Math.round(viewport.centerX + viewport.radius);
            int minZ = (int) Math.round(viewport.centerZ - viewport.radius);
            int maxZ = (int) Math.round(viewport.centerZ + viewport.radius);
            int snap = 256;
            int snappedMinX = floorTo(minX, snap);
            int snappedMinZ = floorTo(minZ, snap);
            int snappedMaxX = ceilTo(maxX, snap);
            int snappedMaxZ = ceilTo(maxZ, snap);
            String key = seed + "|" + mcVersion + "|" + dimension + "|" + snappedMinX + "|" + snappedMinZ + "|" + snappedMaxX + "|" + snappedMaxZ + "|" + selectedStructureIds;
            requestedStructureKey = key;
            List<CubiomesBridge.StructurePoint> cachedPoints = cubiomesStructureCache.get(key);
            if (cachedPoints != null) {
                cachedCubiomesStructures = cachedPoints;
                cachedCubiomesStructureKey = key;
            }
            if (!key.equals(cachedCubiomesStructureKey) && !structureGenInFlight && !interactionDebounceTimer.isRunning()) {
                structureGenInFlight = true;
                final String generateKey = key;
                SwingWorker<List<CubiomesBridge.StructurePoint>, Void> worker = new SwingWorker<>() {
                    @Override
                    protected List<CubiomesBridge.StructurePoint> doInBackground() {
                        if (!cubiomesBridge.ensureInitialized(DEFAULT_CUBIOMES_BRIDGE, DEFAULT_CUBIOMES_DLL)) {
                            return List.of();
                        }
                        int previewLimit = 1200;
                        return cubiomesBridge.generateStructures(
                                seed, mcVersion, dimension,
                                snappedMinX, snappedMinZ, snappedMaxX, snappedMaxZ,
                                selectedStructureIds, previewLimit
                        );
                    }

                    @Override
                    protected void done() {
                        try {
                            List<CubiomesBridge.StructurePoint> generated = get();
                            if (generateKey.equals(requestedStructureKey)) {
                                cachedCubiomesStructures = generated != null ? generated : List.of();
                                cachedCubiomesStructureKey = generateKey;
                                cubiomesStructureCache.put(generateKey, cachedCubiomesStructures);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            structureGenInFlight = false;
                            repaint();
                        }
                    }
                };
                worker.execute();
            }
            for (CubiomesBridge.StructurePoint p : cachedCubiomesStructures) {
                int px = toPixel(p.x(), viewport.centerX, viewport.radius, w);
                int py = toPixel(p.z(), viewport.centerZ, viewport.radius, h);
                if (px < -20 || py < -20 || px > w + 20 || py > h + 20) {
                    continue;
                }
                BufferedImage icon = iconForStructureType(p.type());
                if (icon != null) {
                    int size = Math.max(10, (int) Math.round(14 * iconScale));
                    g2.drawImage(icon, px - size / 2, py - size / 2, size, size, null);
                } else {
                    g2.setColor(new Color(255, 170, 0, 240));
                    int half = Math.max(2, (int) Math.round(3 * iconScale));
                    int size = half * 2 + 1;
                    g2.fillRect(px - half, py - half, size, size);
                }
            }
        }

        private BufferedImage iconForStructureType(int structureType) {
            if (structureIconCache.containsKey(structureType)) {
                return structureIconCache.get(structureType);
            }
            String file = switch (structureType) {
                case 1 -> "desert_pyramid.png";
                case 2 -> "jungle_pyramid.png";
                case 3 -> "swamp_hut.png";
                case 4 -> "igloo.png";
                case 5 -> "village.png";
                case 6 -> "ocean_ruin.png";
                case 7 -> "shipwreck.png";
                case 8 -> "monument.png";
                case 9 -> "mansion.png";
                case 10 -> "pillager_outpost.png";
                case 11 -> "ruined_portal.png";
                case 12 -> "ruined_portal_n.png";
                case 13 -> "ancient_city.png";
                case 14 -> "buried_treasure.png";
                case 15 -> "mineshaft.png";
                case 16 -> "geode.png";
                case 18 -> "fortress.png";
                case 19 -> "bastion_remnant.png";
                case 20 -> "end_city.png";
                case 21 -> "end_gateway.png";
                case 23 -> "trail_ruins.png";
                case 24 -> "trial_chambers.png";
                default -> null;
            };
            BufferedImage icon = null;
            if (file != null) {
                try (InputStream in = LootProbeGui.class.getResourceAsStream("/icons/" + file)) {
                    if (in != null) {
                        icon = javax.imageio.ImageIO.read(in);
                    }
                } catch (Exception ignored) {
                }
            }
            structureIconCache.put(structureType, icon);
            return icon;
        }

        private static int floorTo(int value, int step) {
            return Math.floorDiv(value, step) * step;
        }

        private static int ceilTo(int value, int step) {
            return floorTo(value + step - 1, step);
        }

        private void markViewportChanged() {
            interactionDebounceTimer.restart();
            if (!interactionFrameTimer.isRunning()) {
                interactionFrameTimer.start();
            }
        }

        private boolean isInteracting() {
            return draggingPan || draggingScanRadius || interactionDebounceTimer.isRunning();
        }

        private record RenderFrame(
                BufferedImage image,
                int width,
                int height,
                int centerX,
                int centerZ,
                double radius,
                String baseKey
        ) {
        }

        private void drawCachedImage(Graphics2D g2, int w, int h, Viewport viewport) {
            if (cached == null) {
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, w, h);
                return;
            }
            if (Math.abs(cachedRadius) < 0.0001) {
                g2.drawImage(cached, 0, 0, null);
                return;
            }
            int cw = cached.getWidth();
            int ch = cached.getHeight();
            double worldLeft = viewport.centerX - viewport.radius;
            double worldRight = viewport.centerX + viewport.radius;
            double worldTop = viewport.centerZ - viewport.radius;
            double worldBottom = viewport.centerZ + viewport.radius;

            double cachedLeft = cachedCenterX - cachedRadius;
            double cachedTop = cachedCenterZ - cachedRadius;
            double cachedSpan = cachedRadius * 2.0;
            if (cachedSpan <= 0.0001) {
                g2.drawImage(cached, 0, 0, w, h, null);
                return;
            }

            double srcX1f = ((worldLeft - cachedLeft) / cachedSpan) * cw;
            double srcX2f = ((worldRight - cachedLeft) / cachedSpan) * cw;
            double srcY1f = ((worldTop - cachedTop) / cachedSpan) * ch;
            double srcY2f = ((worldBottom - cachedTop) / cachedSpan) * ch;

            double spanX = srcX2f - srcX1f;
            double spanY = srcY2f - srcY1f;
            if (Math.abs(spanX) < 0.0001 || Math.abs(spanY) < 0.0001) {
                g2.drawImage(cached, 0, 0, w, h, null);
                return;
            }

            int srcX1 = Math.max(0, (int) Math.floor(srcX1f));
            int srcX2 = Math.min(cw, (int) Math.ceil(srcX2f));
            int srcY1 = Math.max(0, (int) Math.floor(srcY1f));
            int srcY2 = Math.min(ch, (int) Math.ceil(srcY2f));

            if (srcX2 <= srcX1 || srcY2 <= srcY1) {
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, w, h);
                return;
            }

            int dx1 = (int) Math.round(((srcX1 - srcX1f) / spanX) * w);
            int dx2 = (int) Math.round(((srcX2 - srcX1f) / spanX) * w);
            int dy1 = (int) Math.round(((srcY1 - srcY1f) / spanY) * h);
            int dy2 = (int) Math.round(((srcY2 - srcY1f) / spanY) * h);

            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, w, h);
            g2.drawImage(cached, dx1, dy1, dx2, dy2, srcX1, srcY1, srcX2, srcY2, null);
        }

        private static long hash(long seed, int x, int z) {
            long v = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
            v ^= (v >>> 33);
            v *= 0xff51afd7ed558ccdL;
            v ^= (v >>> 33);
            v *= 0xc4ceb9fe1a85ec53L;
            v ^= (v >>> 33);
            return v;
        }

        private static Color colorFor(int t) {
            if (t < 48) {
                return new Color(31, 76, 128, 255);
            }
            if (t < 96) {
                return new Color(58, 127, 82, 255);
            }
            if (t < 160) {
                return new Color(104, 150, 79, 255);
            }
            if (t < 215) {
                return new Color(168, 152, 95, 255);
            }
            return new Color(118, 123, 132, 255);
        }
    }

    public static final class GuiSettings {
        public String mcVersion;
        public String seed;
        public String serverJar;
        public String output;
        public String workDir;
        public String startupTimeout;
        public String dimension;
        // Backward-compatible fields from older settings files.
        public String structureDimension;
        public String scanDimension;
        public String scanCenterX;
        public String scanCenterZ;
        public String scanRadius;
        public String locateStep;
        public String extractChunkRadius;
        public boolean extractParallelChunks;
        public String extractParallelChunkCount;
        public String extractParallelStructures;
        public String extractTimeout;
        public String extractStartTimeoutMs;
        public String extractStatusTimeoutMs;
        public String maxStructures;
        public String pluginJar;
        public Boolean cubiomesMap;
        public Boolean cubiomesStructures;
        public String cubiomesDllPath;
        public String cubiomesBridgePath;
        public String mapMaxZoomOut;
        public String mapIconScale;
        public String datapack;
        public boolean autoDatapackStructures;
        public boolean ultraLean;
        public String selectedStructures;
        public List<String> datapacks = new ArrayList<>();
    }

    public static final class SimplifiedResults {
        public long seed;
        public String dimension;
        public int center_x;
        public int center_z;
        public int radius;
        public String minecraftVersion;
        public List<SimplifiedStructure> structures = new ArrayList<>();
    }

    public static final class SimplifiedStructure {
        public String id;
        public String type;
        public int x;
        public int y;
        public int z;
        public List<SimplifiedItem> items = new ArrayList<>();
    }

    public static final class SimplifiedItem {
        public int slot;
        public int count;
        public String itemId;
        public String id;
        public String displayName;
        public String nbt;
        public List<String> enchantments = new ArrayList<>();
        public List<Integer> enchantmentLevels = new ArrayList<>();
    }
}
