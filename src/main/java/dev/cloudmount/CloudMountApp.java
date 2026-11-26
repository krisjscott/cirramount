package dev.cloudmount;

import dev.cloudmount.account.OAuthClientStore;
import dev.cloudmount.config.*;
import dev.cloudmount.mount.MountService;
import dev.cloudmount.rclone.CommandRunner;
import dev.cloudmount.storage.*;
import dev.cloudmount.transfer.*;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class CloudMountApp extends Application {
    private final AppPaths root = AppPaths.system();
    private final AccountStore accounts = new AccountStore(root);
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "mount-poller");
        t.setDaemon(true);
        return t;
    });
    private final MountRegistry mounts = new MountRegistry(accounts, this::log);
    private final ProviderSetup setup = new ProviderSetup();
    private final BooleanProperty busy = new SimpleBooleanProperty(false), configuring = new SimpleBooleanProperty(false), owned = new SimpleBooleanProperty(false), configured = new SimpleBooleanProperty(false);
    private final ListView<CloudAccount> accountList = new ListView<>();
    private final VBox details = new VBox(18), rows = new VBox(12);
    private final Label notice = new Label("Loading saved connections…"), globalStatus = new Label("Cloud storage for Linux"), state = new Label(), connectionState = new Label();
    private final Label speed = new Label("—"), transferred = new Label("—"), queued = new Label("—"), cacheUsed = new Label("—"), transferState = new Label(), storageLabel = new Label();
    private final ProgressBar storageBar = new ProgressBar(0);
    private final TextArea activity = new TextArea();
    private final Hyperlink browserLink = new Hyperlink("Open provider sign-in in your browser");
    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> polling = new ConcurrentHashMap<>();
    private final Map<String, Long> quotaTimes = new ConcurrentHashMap<>();
    private volatile CloudAccount selected;
    private volatile boolean closed;
    private String authUrl;
    private Stage stage;
    private FileChannel lockChannel;
    private FileLock lock;
    private ScrollPane page;
    private TextField mountPath, cachePath, binary;
    private Spinner<Integer> budget, age, delay;
    // Snapshot is maintained on the FX thread so the polling thread never reads JavaFX collections.
    private volatile List<CloudAccount> cachedAccounts = List.of();

    public static void main(String[] args) {
        launch(args);
    }

    private static Map<String, String> values(Map<String, TextInputControl> inputs) {
        var out = new LinkedHashMap<String, String>();
        inputs.forEach((key, input) -> out.put(key, input.getText()));
        return out;
    }

    private static Label label(String value, String css) {
        Label l = new Label(value);
        l.getStyleClass().add(css);
        return l;
    }

    private static Label wrap(String value, String css) {
        Label l = label(value, css);
        l.setWrapText(true);
        return l;
    }

    private static VBox field(String name, Node control) {
        return new VBox(6, label(name, "field-label"), control);
    }

    private static VBox metric(String name, Label value) {
        value.getStyleClass().setAll("metric-value");
        return new VBox(5, label(name, "help"), value);
    }

    private static Button button(String text, String css, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(css);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static VBox panel(String title, Node... nodes) {
        VBox v = new VBox(13);
        v.getStyleClass().add("panel");
        v.getChildren().add(label(title, "section-title"));
        v.getChildren().addAll(nodes);
        return v;
    }

    @Override
    public void start(Stage primary) throws Exception {
        stage = primary;
        PrivateFiles.directory(root.configDir());
        lockChannel = FileChannel.open(root.configDir().resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            new Alert(Alert.AlertType.INFORMATION, "CloudMount is already open.", ButtonType.OK).showAndWait();
            Platform.exit();
            return;
        }
        Scene scene = new Scene(shell(), 1280, 900);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/dev/cloudmount/cloudmount.css")).toExternalForm());
        stage.setTitle("CloudMount — Your cloud storage");
        stage.setMinWidth(1080);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            e.consume();
            close();
        });
        stage.show();
        task("Loading connections…", () -> {
            var list = accounts.list();
            for (var a : list) {
                try {
                    if (mounts.service(a).restore()) statuses.put(a.id(), "Mounted in background");
                } catch (Exception e) {
                    statuses.put(a.id(), "Needs attention");
                    log(a.name() + ": " + e.getMessage());
                }
            }
            return list;
        }, list -> {
            accountList.getItems().setAll(list);
            if (!list.isEmpty()) accountList.getSelectionModel().selectFirst();
            else empty();
            message("Ready. Add storage or select a connection.", false);
        });
        timer.scheduleWithFixedDelay(this::poll, 2, 2, TimeUnit.SECONDS);
        String output = getParameters().getNamed().get("smoke-output");
        if (output != null) {
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(Double.parseDouble(getParameters().getNamed().getOrDefault("smoke-delay", "3"))));
            pause.setOnFinished(e -> {
                if (getParameters().getUnnamed().contains("--smoke-settings")) page.setVvalue(1);
                Platform.runLater(() -> {
                    try {
                        snapshot(Path.of(output));
                    } catch (Exception x) {
                        x.printStackTrace();
                    }
                    shutdown();
                });
            });
            pause.play();
        }
    }

    private Parent shell() {
        VBox identity = new VBox(4, label("CloudMount", "brand"), label("All your storage, at home on Linux.", "tagline"));
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        globalStatus.getStyleClass().add("status-badge");
        HBox header = new HBox(20, identity, space, globalStatus);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        accountList.setItems(FXCollections.observableArrayList());
        accountList.getItems().addListener((javafx.collections.ListChangeListener<CloudAccount>) change -> {
            cachedAccounts = List.copyOf(accountList.getItems());
            updateCount();
        });
        accountList.disableProperty().bind(busy);
        accountList.setPlaceholder(label("No connections yet", "help"));
        accountList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(CloudAccount a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label name = label(a.name(), "file-name");
                    name.setWrapText(true);
                    Label provider = label(a.provider().toString(), "help");
                    Label status = label(statuses.getOrDefault(a.id(), Files.exists(accounts.paths(a).credentials()) ? "Ready to mount" : "Not connected"), "help");
                    setGraphic(new VBox(5, name, provider, status));
                    setText(null);
                }
            }
        });
        accountList.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> {
            selected = value;
            if (value != null) show(value);
            else empty();
        });
        Button add = button("Add storage", "primary", this::add);
        add.setMaxWidth(Double.MAX_VALUE);
        add.disableProperty().bind(busy);
        VBox sidebar = new VBox(14, label("Connections", "section-title"), accountList, add);
        sidebar.setPadding(new Insets(24, 16, 24, 20));
        sidebar.setPrefWidth(250);
        sidebar.setMinWidth(220);
        VBox.setVgrow(accountList, Priority.ALWAYS);
        sidebar.getStyleClass().add("sidebar");
        notice.setWrapText(true);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.getStyleClass().add("notice");
        details.setPadding(new Insets(24));
        page = new ScrollPane(details);
        page.setFitToWidth(true);
        page.getStyleClass().add("page-scroll");
        VBox main = new VBox(12, notice, page);
        main.setPadding(new Insets(18, 18, 0, 0));
        VBox.setVgrow(page, Priority.ALWAYS);
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox body = new HBox(12, sidebar, main);
        VBox.setVgrow(body, Priority.ALWAYS);
        Label footer = label("Close the window to leave mounts running. Unmount stops only the selected connection.", "footer");
        footer.setPadding(new Insets(12, 24, 12, 24));
        footer.setMaxWidth(Double.MAX_VALUE);
        VBox shell = new VBox(header, body, footer);
        shell.getStyleClass().add("app");
        return shell;
    }

    private void empty() {
        selected = null;
        details.getChildren().setAll(label("Connect your cloud storage", "page-title"), wrap("Add an account, choose a local folder, and mount your files. Each connection has its own credentials, cache and background mount.", "muted"),
                panel("Choose your provider", wrap("Google Drive · Amazon S3 · S3-compatible storage · OneDrive · Dropbox · WebDAV · SFTP", "muted"), wrap("You can add multiple accounts from the same provider and keep them mounted together.", "help"), button("Add storage", "primary", this::add)));
    }

    private void show(CloudAccount a) {
        try {
            AppPaths paths = accounts.paths(a);
            MountService service = mounts.service(a);
            MountSettings s = service.activeSettings();
            if (s == null) s = new SettingsStore(paths).load();
            owned.set(service.ownsMount());
            configured.set(Files.exists(paths.credentials()));
            state.setText(statuses.getOrDefault(a.id(), owned.get() ? "Mounted in background" : "Not mounted"));
            state.getStyleClass().setAll("status-badge");
            HBox title = new HBox(20, label(a.name(), "page-title"), state);
            title.setAlignment(Pos.CENTER_LEFT);
            Button mount = button("Mount", "primary", () -> mount(a));
            mount.disableProperty().bind(busy.or(owned).or(configured.not()));
            Button unmount = button("Unmount", "secondary", () -> task("Unmounting " + a.name() + "…", () -> {
                service.unmount();
                return null;
            }, v -> {
                statuses.remove(a.id());
                show(a);
                message(a.name() + " unmounted. Other mounts are unchanged.", false);
            }));
            unmount.disableProperty().bind(busy.or(owned.not()));
            Button open = button("Open folder", "secondary", () -> getHostServices().showDocument(Path.of(mountPath.getText()).toUri().toString()));
            open.disableProperty().bind(busy.or(owned.not()));
            Button close = button("Close window", "quiet", this::close);
            close.disableProperty().bind(busy);
            HBox actions = new HBox(10, mount, unmount, open, close);
            connectionState.setText(configured.get() ? "Credentials saved for this connection." : "Connect this account before mounting.");
            connectionState.getStyleClass().setAll("muted");
            Button connect = button(a.provider() == Provider.GOOGLE ? "Sign in with Google" : a.provider().oauth() ? "Sign in with " + a.provider() : "Configure connection", "google-signin", () -> configure(a));
            connect.setMaxWidth(Double.MAX_VALUE);
            connect.disableProperty().bind(busy.or(owned));
            Button verify = button("Check connection", "quiet", () -> verify(a));
            verify.disableProperty().bind(busy.or(owned).or(configured.not()));
            Button remove = button("Remove connection", "quiet", () -> remove(a));
            remove.disableProperty().bind(busy.or(owned));
            browserLink.setVisible(false);
            browserLink.setManaged(false);
            browserLink.setOnAction(e -> {
                if (authUrl != null) getHostServices().showDocument(authUrl);
            });
            Button cancel = button("Cancel setup", "quiet", setup::cancel);
            cancel.visibleProperty().bind(configuring);
            cancel.managedProperty().bind(configuring);
            VBox connection = panel("Connection", connectionState, wrap(a.provider() + " · " + (a.remotePath().isBlank() ? "Account root" : a.remotePath()), "help"), connect, browserLink, cancel, new HBox(14, verify, remove));
            if (a.provider() == Provider.GOOGLE) {
                Button client = button("Google app setup…", "quiet", this::importGoogleClient);
                client.disableProperty().bind(busy);
                connection.getChildren().add(client);
            }
            VBox left = new VBox(20, connection, settings(a, s));
            left.setMinWidth(340);
            left.setPrefWidth(430);
            VBox right = transfers();
            right.setMinWidth(290);
            right.setPrefWidth(360);
            right.setMaxHeight(Region.USE_PREF_SIZE);
            HBox columns = new HBox(20, left, right);
            HBox.setHgrow(left, Priority.ALWAYS);
            HBox.setHgrow(right, Priority.ALWAYS);
            details.getChildren().setAll(title, label(a.provider().toString(), "muted"), actions, columns);
            accountList.refresh();
        } catch (Exception e) {
            message(e.getMessage(), true);
        }
    }

    private VBox settings(CloudAccount a, MountSettings s) {
        mountPath = new TextField(s.mountPath());
        cachePath = new TextField(s.cachePath());
        binary = new TextField(s.rcloneBinary());
        budget = new Spinner<>(1, 10000, s.cacheGiB());
        age = new Spinner<>(1, 8760, s.maxAgeHours());
        delay = new Spinner<>(1, 600, s.writeBackSeconds());
        for (Control c : List.of(mountPath, cachePath, binary, budget, age, delay))
            c.disableProperty().bind(busy.or(owned));
        FlowPane limits = new FlowPane(12, 12, field("Budget (GiB)", budget), field("Unused age (h)", age), field("Upload delay (s)", delay));
        Button save = button("Save settings", "secondary", () -> {
            try {
                var draft = draft();
                task("Saving settings…", () -> {
                    mounts.validateLocations(a, draft);
                    new SettingsStore(accounts.paths(a)).save(draft);
                    return null;
                }, v -> message("Settings saved for " + a.name() + ".", false));
            } catch (Exception e) {
                message(e.getMessage(), true);
            }
        });
        save.disableProperty().bind(busy.or(owned));
        return panel("Mount & cache", field("Mount folder", browse(mountPath)), field("Cache folder", browse(cachePath)), limits, wrap("Cache is separate from cloud quota. Pending writes can exceed the budget; keep local disk space available.", "help"), field("rclone executable", binary), save);
    }

    private VBox transfers() {
        transferState.setText("Mount this connection to see activity.");
        transferState.setWrapText(true);
        storageLabel.setText("Quota appears when the provider reports it.");
        storageLabel.setWrapText(true);
        storageLabel.getStyleClass().setAll("help");
        storageBar.setVisible(false);
        storageBar.setManaged(false);
        storageBar.setMaxWidth(Double.MAX_VALUE);
        speed.setText("—");
        transferred.setText("—");
        queued.setText("—");
        cacheUsed.setText("—");
        rows.getChildren().setAll(wrap("No active transfers", "empty-title"));
        activity.setEditable(false);
        activity.setWrapText(true);
        activity.setPrefRowCount(6);
        FlowPane metrics = new FlowPane(24, 16, metric("Speed", speed), metric("Transferred", transferred), metric("Uploads queued", queued), metric("Cache used", cacheUsed));
        return panel("Transfers", transferState, label("Storage quota", "section-small"), storageLabel, storageBar, metrics, new Separator(), rows, new Separator(), label("Activity · all connections", "section-small"), activity);
    }

    private void add() {
        Dialog<CloudAccount> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Add storage");
        TextField name = new TextField();
        name.setPromptText("Personal Drive, Work backups…");
        ComboBox<Provider> provider = new ComboBox<>(FXCollections.observableArrayList(Provider.values()));
        provider.setValue(Provider.GOOGLE);
        provider.setMaxWidth(Double.MAX_VALUE);
        TextField path = new TextField();
        path.setPromptText("S3: bucket/prefix · other providers: optional folder");
        Label error = wrap("", "help");
        VBox form = new VBox(14, field("Connection name", name), field("Provider", provider), field("Bucket or remote folder", path), wrap("Choose separate local folders for each connection. Credentials are entered in the next step.", "help"), error);
        form.setPrefWidth(440);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.NEXT);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.NEXT)).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                new CloudAccount(UUID.randomUUID().toString(), name.getText(), provider.getValue(), path.getText());
            } catch (Exception x) {
                error.setText(x.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.NEXT ? new CloudAccount(UUID.randomUUID().toString(), name.getText(), provider.getValue(), path.getText()) : null);
        dialog.showAndWait().ifPresent(a -> task("Adding connection…", () -> accounts.add(a.name(), a.provider(), a.remotePath()), created -> {
            accountList.getItems().add(created);
            accountList.getSelectionModel().select(created);
            message("Connection added. Configure " + created.provider() + " to continue.", false);
            Platform.runLater(() -> configure(created));
        }));
    }

    private void configure(CloudAccount a) {
        if (a.provider() == Provider.GOOGLE) {
            connect(a, a.provider().parameters(Map.of()));
            return;
        }
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Connect " + a.name());
        Map<String, TextInputControl> inputs = new LinkedHashMap<>();
        VBox fields = new VBox(14);
        fields.getChildren().add(wrap(a.provider().oauth() ? "Complete provider authorization in your browser. Additional account or drive choices will appear here if needed." : "These credentials are saved privately for this connection. They are never placed in command arguments.", "muted"));
        for (var f : a.provider().fields()) {
            TextInputControl input = f.secret() ? new PasswordField() : new TextField(f.initial());
            if (f.secret()) input.setText(f.initial());
            inputs.put(f.key(), input);
            fields.getChildren().add(field(f.label(), input));
        }
        Label error = wrap("", "help");
        fields.getChildren().add(error);
        fields.setPrefWidth(480);
        ScrollPane scroll = new ScrollPane(fields);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(520, 100 + a.provider().fields().size() * 72));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                a.provider().parameters(values(inputs));
            } catch (Exception x) {
                error.setText(x.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? a.provider().parameters(values(inputs)) : null);
        dialog.showAndWait().ifPresent(params -> connect(a, params));
    }

    private void connect(CloudAccount a, Map<String, String> params) {
        String engine = binary.getText().strip();
        configuring.set(true);
        task("Connecting " + a.name() + "…", () -> {
            if (a.provider() == Provider.GOOGLE && !params.containsKey("client_id") && !params.containsKey("client_secret")) {
                OAuthClientStore.Client client;
                try {
                    client = new OAuthClientStore(root).load();
                } catch (java.io.IOException e) {
                    client = new OAuthClientStore(accounts.paths(a)).load();
                }
                params.put("client_id", client.id());
                params.put("client_secret", client.secret());
            }
            if (a.provider() == Provider.GOOGLE && (!params.containsKey("client_id") || !params.containsKey("client_secret")))
                throw new IllegalArgumentException("Configure both Google client ID and secret, or import the app client using Google app setup.");
            setup.configure(a, accounts.paths(a), engine, params, this::ask, uri -> Platform.runLater(() -> {
                authUrl = uri.toString();
                browserLink.setVisible(true);
                browserLink.setManaged(true);
                getHostServices().showDocument(authUrl);
            }));
            return null;
        }, v -> {
            statuses.remove(a.id());
            show(a);
            message(a.name() + " connected. Choose your local folders and mount.", false);
        });
    }

    private String ask(ProviderSetup.Question q) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            Dialog<String> d = new Dialog<>();
            d.initOwner(stage);
            d.setTitle("Provider setup · " + q.name());
            TextInputControl text = q.password() ? new PasswordField() : new TextField(q.value());
            ComboBox<ProviderSetup.Choice> choices = new ComboBox<>(FXCollections.observableArrayList(q.choices()));
            choices.setMaxWidth(Double.MAX_VALUE);
            for (var c : q.choices()) if (c.value().equals(q.value())) choices.setValue(c);
            boolean pick = q.exclusive() && !q.choices().isEmpty();
            if (pick && choices.getValue() == null) choices.getSelectionModel().selectFirst();
            Label help = wrap(q.help(), "muted");
            Label validation = wrap("", "help");
            VBox body = new VBox(14, help, pick ? choices : text, validation);
            body.setPrefWidth(480);
            ScrollPane pane = new ScrollPane(body);
            pane.setFitToWidth(true);
            pane.setPrefViewportHeight(340);
            d.getDialogPane().setContent(pane);
            d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            Supplier<String> result = () -> pick ? (choices.getValue() == null ? "" : choices.getValue().value()) : text.getText();
            ((Button) d.getDialogPane().lookupButton(ButtonType.OK)).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                if (q.required() && result.get().isBlank()) {
                    validation.setText("A value is required.");
                    e.consume();
                }
            });
            d.setResultConverter(b -> b == ButtonType.OK ? result.get() : null);
            answer.complete(d.showAndWait().orElse(null));
        });
        return answer.join();
    }

    private void verify(CloudAccount a) {
        String engine = binary.getText().strip();
        task("Checking " + a.name() + "…", () -> {
            var r = new CommandRunner().run(List.of(engine, "lsf", a.remote(), "--dirs-only", "--max-depth", "1", "--config", accounts.paths(a).credentials().toString(), "--contimeout", "10s", "--timeout", "20s", "--retries", "1"), Map.of(), Duration.ofSeconds(40));
            CommandRunner.requireSuccess(r, "Connection check");
            return null;
        }, v -> {
            connectionState.setText("Connection verified.");
            message(a.name() + " is accessible.", false);
        });
    }

    private void importGoogleClient() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Google Desktop app credentials");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Google credentials JSON", "*.json"));
        var file = chooser.showOpenDialog(stage);
        if (file != null) task("Importing Google app setup…", () -> {
            new OAuthClientStore(root).importFile(file.toPath());
            return null;
        }, v -> message("Google app setup saved. Sign in to this connection to continue.", false));
    }

    private void remove(CloudAccount a) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Remove '" + a.name() + "' and its saved credentials? Cloud files and local cache will be kept.", ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) task("Removing connection…", () -> {
            accounts.remove(a);
            return null;
        }, v -> {
            accountList.getItems().remove(a);
            message("Connection removed. Cloud files and cache kept.", false);
        });
    }

    private MountSettings draft() {
        var s = new MountSettings(binary.getText().strip(), mountPath.getText().strip(), cachePath.getText().strip(), budget.getValue(), age.getValue(), delay.getValue());
        s.validate();
        return s;
    }

    private void mount(CloudAccount a) {
        try {
            var s = draft();
            task("Mounting " + a.name() + "…", () -> {
                mounts.mount(a, s);
                new SettingsStore(accounts.paths(a)).save(s);
                return null;
            }, v -> {
                statuses.put(a.id(), "Mounted in background");
                quotaTimes.remove(a.id());
                show(a);
                message(a.name() + " mounted. You can close the window; all mounts keep running.", false);
            });
        } catch (Exception e) {
            message(e.getMessage(), true);
        }
    }

    private <T> void task(String message, Callable<T> work, Consumer<T> success) {
        if (busy.get()) return;
        busy.set(true);
        message(message, false);
        io.submit(() -> {
            T result = null;
            Exception failure = null;
            try {
                result = work.call();
            } catch (Exception e) {
                failure = e;
            }
            T value = result;
            Exception error = failure;
            boolean active = selected != null && mounts.service(selected).ownsMount();
            boolean saved = selected != null && Files.exists(accounts.paths(selected).credentials());
            Platform.runLater(() -> {
                configuring.set(false);
                busy.set(false);
                owned.set(active);
                configured.set(saved);
                browserLink.setVisible(false);
                browserLink.setManaged(false);
                if (error != null) {
                    message(error.getMessage() == null ? "The operation failed. Review the connection settings." : error.getMessage(), true);
                    log(error.getMessage() == null ? "Operation failed." : error.getMessage());
                } else success.accept(value);
                accountList.refresh();
            });
        });
    }

    private void poll() {
        if (closed) return;
        for (CloudAccount a : List.copyOf(accountListSnapshot())) {
            MountService service = mounts.service(a);
            if (!service.ownsMount()) continue;
            var flag = polling.computeIfAbsent(a.id(), id -> new AtomicBoolean());
            if (!flag.compareAndSet(false, true)) continue;
            io.submit(() -> {
                try {
                    var snapshot = service.snapshot();
                    statuses.put(a.id(), "Mounted in background");
                    Platform.runLater(() -> {
                        if (current(a) && !busy.get()) {
                            state.setText("Mounted in background");
                            render(snapshot);
                        }
                        accountList.refresh();
                        updateCount();
                    });
                    if (System.currentTimeMillis() - quotaTimes.getOrDefault(a.id(), 0L) > 30000) {
                        quotaTimes.put(a.id(), System.currentTimeMillis());
                        try {
                            var quota = service.storage();
                            Platform.runLater(() -> {
                                if (current(a)) {
                                    storageLabel.setText(quota.label());
                                    storageBar.setProgress(quota.fraction());
                                    storageBar.setVisible(quota.known());
                                    storageBar.setManaged(quota.known());
                                }
                            });
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                if (current(a)) {
                                    storageLabel.setText("Quota unavailable for this provider. Transfers are still monitored.");
                                    storageBar.setVisible(false);
                                    storageBar.setManaged(false);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    statuses.put(a.id(), "Needs attention");
                    Platform.runLater(() -> {
                        if (current(a) && !busy.get()) {
                            state.setText("Needs attention");
                            transferState.setText("Status unavailable; upload completion is unknown.");
                        }
                        accountList.refresh();
                        updateCount();
                    });
                } finally {
                    flag.set(false);
                }
            });
        }
    }

    private List<CloudAccount> accountListSnapshot() {
        return cachedAccounts;
    }

    private void updateCount() {
        long count = statuses.values().stream().filter(s -> s.equals("Mounted in background")).count();
        globalStatus.setText(count + " mounted");
    }

    private boolean current(CloudAccount a) {
        return selected != null && selected.id().equals(a.id());
    }

    private void render(TransferSnapshot s) {
        transferState.setText(s.status());
        speed.setText(TransferSnapshot.bytes((long) s.speed()) + "/s");
        transferred.setText(TransferSnapshot.bytes(s.bytesTransferred()));
        queued.setText(Integer.toString(s.queued()));
        cacheUsed.setText(TransferSnapshot.bytes(s.cacheBytes()));
        rows.getChildren().clear();
        if (s.items().isEmpty()) rows.getChildren().add(label("No active transfers", "empty-title"));
        for (var item : s.items().stream().limit(10).toList()) {
            VBox row = new VBox(5, wrap(item.name(), "file-name"), wrap(item.status() + " · " + item.detail(), "help"));
            if (item.progress() >= 0) {
                ProgressBar p = new ProgressBar(item.progress());
                p.setMaxWidth(Double.MAX_VALUE);
                row.getChildren().add(p);
            }
            rows.getChildren().add(row);
        }
    }

    private void close() {
        if (busy.get()) {
            message("Wait for the current operation to finish, or cancel setup before closing.", true);
            return;
        }
        shutdown();
    }

    private void shutdown() {
        closed = true;
        setup.cancel();
        timer.shutdownNow();
        io.shutdown();
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (Exception ignored) {
        }
        stage.hide();
        Platform.exit();
    }

    private void message(String text, boolean error) {
        notice.setText(CommandRunner.redact(text == null ? "Operation failed." : text));
        notice.getStyleClass().remove("notice-error");
        if (error) notice.getStyleClass().add("notice-error");
    }

    private void log(String text) {
        if (closed) return;
        Platform.runLater(() -> {
            String value = activity.getText() + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + CommandRunner.redact(text == null ? "Operation failed." : text) + "\n";
            activity.setText(value.length() > 16000 ? value.substring(value.length() - 12000) : value);
            activity.positionCaret(activity.getLength());
        });
    }

    private HBox browse(TextField field) {
        Button button = button("Browse…", "secondary", () -> {
            DirectoryChooser picker = new DirectoryChooser();
            var dir = picker.showDialog(stage);
            if (dir != null) field.setText(dir.getAbsolutePath());
        });
        button.disableProperty().bind(busy.or(owned));
        HBox row = new HBox(8, field, button);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private void snapshot(Path target) throws Exception {
        WritableImage image = stage.getScene().snapshot(null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Files.createDirectories(target.toAbsolutePath().getParent());
        ImageIO.write(output, "png", target.toFile());
    }
}
