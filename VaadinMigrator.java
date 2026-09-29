import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;

/**
 * VaadinMigrator – konvertiert Vaadin-7/8-Quellcode so weit wie automatisch möglich nach Vaadin 25.x (Flow).
 *
 * <pre>
 *   java VaadinMigrator.java &lt;quellverzeichnis|Datei.java&gt; &lt;zielverzeichnis|Datei.java&gt; [--vaadin-version=25.3.0] [--dry-run]
 * </pre>
 *
 * Das Quellprojekt wird nie verändert: alles wird ins Ziel kopiert, .java-Dateien und pom.xml werden dabei
 * umgeschrieben. Was nicht sicher automatisch umgesetzt werden kann, wird mit
 * {@code // TODO [Vaadin-Migration]: ...} markiert und in MIGRATION_REPORT.md aufgelistet.
 *
 * Arbeitsweise: String-Literale und Kommentare werden vor der Transformation maskiert (damit z.B. der Text
 * "Window" in einem Button nicht zu "Dialog" wird), danach laufen regelbasierte Umschreibungen mit
 * Klammer-Matching und einfacher Typerkennung für lokale Variablen/Felder.
 * Es ist bewusst ein textbasierter Konverter ohne Abhängigkeiten – kein vollständiger Java-Parser.
 */
public final class VaadinMigrator {

    static final String TODO_PREFIX = "TODO [Vaadin-Migration]: ";
    static final Set<String> SKIP_DIRS = Set.of(".git", "target", "build", "node_modules", ".idea", ".gradle", ".settings");
    static String targetVersion = "25.3.0";
    /** Erzwungene Eingabe-/Ausgabekodierung (null = automatisch erkennen bzw. beibehalten). */
    static Charset forcedInput, forcedOutput;
    static final Charset WINDOWS_1252 = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;

    // ------------------------------------------------------------------------------------------------
    // Typ-Zuordnungen:  alter FQN -> neuer FQN ("-" = entfällt) [ ; Hinweis für TODO ]
    // com.vaadin.ui.* gilt automatisch auch für com.vaadin.v7.ui.* (Compatibility-Pakete)
    // ------------------------------------------------------------------------------------------------
    static final String TYPE_TABLE = """
        com.vaadin.ui.Button -> com.vaadin.flow.component.button.Button
        com.vaadin.ui.TextField -> com.vaadin.flow.component.textfield.TextField
        com.vaadin.ui.TextArea -> com.vaadin.flow.component.textfield.TextArea
        com.vaadin.ui.PasswordField -> com.vaadin.flow.component.textfield.PasswordField
        com.vaadin.ui.CheckBox -> com.vaadin.flow.component.checkbox.Checkbox
        com.vaadin.ui.CheckBoxGroup -> com.vaadin.flow.component.checkbox.CheckboxGroup
        com.vaadin.ui.RadioButtonGroup -> com.vaadin.flow.component.radiobutton.RadioButtonGroup
        com.vaadin.ui.OptionGroup -> com.vaadin.flow.component.radiobutton.RadioButtonGroup ; OptionGroup (V7): bei Mehrfachauswahl CheckboxGroup verwenden, Container-API durch setItems ersetzen
        com.vaadin.ui.ComboBox -> com.vaadin.flow.component.combobox.ComboBox
        com.vaadin.ui.NativeSelect -> com.vaadin.flow.component.select.Select ; NativeSelect -> Select: Konstruktor-Signaturen und Leerauswahl prüfen
        com.vaadin.ui.ListSelect -> com.vaadin.flow.component.listbox.MultiSelectListBox ; ListSelect -> MultiSelectListBox: kein Label/keine Zeilenzahl, alternativ MultiSelectComboBox
        com.vaadin.ui.TwinColSelect -> com.vaadin.flow.component.combobox.MultiSelectComboBox ; TwinColSelect hat kein Gegenstück – MultiSelectComboBox als Ersatz, Verhalten prüfen
        com.vaadin.ui.DateField -> com.vaadin.flow.component.datepicker.DatePicker
        com.vaadin.ui.InlineDateField -> com.vaadin.flow.component.datepicker.DatePicker ; InlineDateField: kein Inline-Kalender in Flow, DatePicker öffnet ein Popup
        com.vaadin.ui.DateTimeField -> com.vaadin.flow.component.datetimepicker.DateTimePicker
        com.vaadin.ui.InlineDateTimeField -> com.vaadin.flow.component.datetimepicker.DateTimePicker ; Inline-Variante existiert nicht
        com.vaadin.ui.Slider -> - ; Slider: kein gesichertes Core-Pendant – NumberField/IntegerField mit setStep oder ein Add-on verwenden
        com.vaadin.ui.RichTextArea -> com.vaadin.flow.component.richtexteditor.RichTextEditor ; RichTextEditor ist eine kommerzielle Komponente (Lizenz prüfen), HTML-Wert über asHtml()
        com.vaadin.ui.Grid -> com.vaadin.flow.component.grid.Grid
        com.vaadin.ui.TreeGrid -> com.vaadin.flow.component.treegrid.TreeGrid
        com.vaadin.ui.Tree -> com.vaadin.flow.component.treegrid.TreeGrid ; Tree -> TreeGrid: Hierarchie-Spalte mit addHierarchyColumn anlegen
        com.vaadin.ui.Table -> com.vaadin.flow.component.grid.Grid ; Table (V7) -> Grid: Container-API existiert nicht mehr, auf setItems/DataProvider umbauen
        com.vaadin.ui.Label -> com.vaadin.flow.component.html.Span
        com.vaadin.ui.VerticalLayout -> com.vaadin.flow.component.orderedlayout.VerticalLayout
        com.vaadin.ui.HorizontalLayout -> com.vaadin.flow.component.orderedlayout.HorizontalLayout
        com.vaadin.ui.FormLayout -> com.vaadin.flow.component.formlayout.FormLayout ; FormLayout ist in Flow responsiv mehrspaltig – ggf. setResponsiveSteps(new ResponsiveStep("0", 1))
        com.vaadin.ui.CssLayout -> com.vaadin.flow.component.html.Div
        com.vaadin.ui.AbsoluteLayout -> com.vaadin.flow.component.html.Div ; AbsoluteLayout -> Div: Positionierung per CSS (position: absolute) nachbauen
        com.vaadin.ui.GridLayout -> com.vaadin.flow.component.html.Div ; GridLayout -> Div: mit CSS Grid (display: grid) oder FormLayout nachbauen
        com.vaadin.ui.Panel -> com.vaadin.flow.component.html.Div ; Panel -> Div: Rahmen per CSS oder Card, Scrollen mit Scroller
        com.vaadin.ui.HorizontalSplitPanel -> com.vaadin.flow.component.splitlayout.SplitLayout
        com.vaadin.ui.VerticalSplitPanel -> com.vaadin.flow.component.splitlayout.SplitLayout
        com.vaadin.ui.TabSheet -> com.vaadin.flow.component.tabs.TabSheet
        com.vaadin.ui.Accordion -> com.vaadin.flow.component.accordion.Accordion
        com.vaadin.ui.Window -> com.vaadin.flow.component.dialog.Dialog
        com.vaadin.ui.Notification -> com.vaadin.flow.component.notification.Notification
        com.vaadin.ui.Upload -> com.vaadin.flow.component.upload.Upload ; Upload: Receiver/Listener auf UploadHandler (seit 24.8) umbauen
        com.vaadin.ui.Image -> com.vaadin.flow.component.html.Image
        com.vaadin.ui.Link -> com.vaadin.flow.component.html.Anchor
        com.vaadin.ui.BrowserFrame -> com.vaadin.flow.component.html.IFrame
        com.vaadin.ui.Embedded -> com.vaadin.flow.component.html.Div ; Embedded: je nach Inhalt Image, IFrame oder Html verwenden
        com.vaadin.ui.Video -> - ; Video: über ein eigenes Element (new Element("video")) oder ein Add-on abbilden
        com.vaadin.ui.Audio -> - ; Audio: über ein eigenes Element (new Element("audio")) oder ein Add-on abbilden
        com.vaadin.ui.MenuBar -> com.vaadin.flow.component.menubar.MenuBar
        com.vaadin.ui.ContextMenu -> com.vaadin.flow.component.contextmenu.ContextMenu ; ContextMenu: API unterscheidet sich (setTarget, addItem mit Listener)
        com.vaadin.ui.ProgressBar -> com.vaadin.flow.component.progressbar.ProgressBar
        com.vaadin.ui.Composite -> com.vaadin.flow.component.Composite
        com.vaadin.ui.CustomComponent -> com.vaadin.flow.component.Composite
        com.vaadin.ui.CustomField -> com.vaadin.flow.component.customfield.CustomField ; CustomField: initContent -> Konstruktor, doSetValue -> setPresentationValue, getValue -> generateModelValue
        com.vaadin.ui.CustomLayout -> com.vaadin.flow.component.Html ; CustomLayout: HTML-Template über Html-Komponente oder Lit-Template abbilden
        com.vaadin.ui.Component -> com.vaadin.flow.component.Component
        com.vaadin.ui.AbstractComponent -> com.vaadin.flow.component.Component ; AbstractComponent: passende Basisklasse bzw. Interface wählen
        com.vaadin.ui.AbstractField -> com.vaadin.flow.component.AbstractField ; AbstractField: API grundlegend anders (AbstractField<C,T>, CustomField bevorzugen)
        com.vaadin.ui.AbstractOrderedLayout -> com.vaadin.flow.component.orderedlayout.FlexComponent
        com.vaadin.ui.AbstractLayout -> com.vaadin.flow.component.HasComponents
        com.vaadin.ui.Layout -> com.vaadin.flow.component.HasComponents
        com.vaadin.ui.ComponentContainer -> com.vaadin.flow.component.HasComponents
        com.vaadin.ui.SingleComponentContainer -> com.vaadin.flow.component.HasComponents
        com.vaadin.ui.HasComponents -> com.vaadin.flow.component.HasComponents
        com.vaadin.ui.UI -> com.vaadin.flow.component.UI
        com.vaadin.ui.ItemCaptionGenerator -> com.vaadin.flow.component.ItemLabelGenerator
        com.vaadin.ui.Alignment -> -
        com.vaadin.ui.JavaScript -> -
        com.vaadin.ui.renderers.NumberRenderer -> com.vaadin.flow.data.renderer.NumberRenderer ; Renderer: in Flow bekommt der Konstruktor den ValueProvider mit übergeben
        com.vaadin.ui.renderers.LocalDateRenderer -> com.vaadin.flow.data.renderer.LocalDateRenderer ; Renderer: in Flow bekommt der Konstruktor den ValueProvider mit übergeben
        com.vaadin.ui.renderers.LocalDateTimeRenderer -> com.vaadin.flow.data.renderer.LocalDateTimeRenderer ; Renderer: in Flow bekommt der Konstruktor den ValueProvider mit übergeben
        com.vaadin.ui.renderers.TextRenderer -> com.vaadin.flow.data.renderer.TextRenderer ; Renderer-Konstruktor prüfen
        com.vaadin.ui.renderers.ComponentRenderer -> -
        com.vaadin.ui.renderers.ButtonRenderer -> - ; ButtonRenderer: addComponentColumn(item -> new Button(...)) verwenden
        com.vaadin.ui.renderers.HtmlRenderer -> - ; HtmlRenderer: LitRenderer oder addComponentColumn mit Html verwenden
        com.vaadin.ui.renderers.ImageRenderer -> - ; ImageRenderer: addComponentColumn mit Image verwenden
        com.vaadin.ui.renderers.DateRenderer -> - ; DateRenderer: auf LocalDate(Time)Renderer umstellen
        com.vaadin.ui.renderers.ProgressBarRenderer -> - ; ProgressBarRenderer: addComponentColumn mit ProgressBar verwenden
        com.vaadin.ui.components.grid.HeaderRow -> com.vaadin.flow.component.grid.HeaderRow
        com.vaadin.ui.components.grid.FooterRow -> com.vaadin.flow.component.grid.FooterRow
        com.vaadin.ui.components.grid.ItemClickListener -> - ; ItemClickListener: addItemClickListener bzw. addItemDoubleClickListener mit Lambda
        com.vaadin.ui.declarative.Design -> - ; Declarative Design wird nicht unterstützt – UI in Java nachbauen
        com.vaadin.ui.themes.ValoTheme -> -
        com.vaadin.ui.themes.Reindeer -> - ; Reindeer-Theme existiert nicht mehr – Lumo/Aura verwenden
        com.vaadin.ui.themes.Runo -> - ; Runo-Theme existiert nicht mehr – Lumo/Aura verwenden
        com.vaadin.data.Binder -> com.vaadin.flow.data.binder.Binder
        com.vaadin.data.BeanValidationBinder -> com.vaadin.flow.data.binder.BeanValidationBinder
        com.vaadin.data.BinderValidationStatus -> com.vaadin.flow.data.binder.BinderValidationStatus
        com.vaadin.data.BindingValidationStatus -> com.vaadin.flow.data.binder.BindingValidationStatus
        com.vaadin.data.ValidationException -> com.vaadin.flow.data.binder.ValidationException
        com.vaadin.data.ValidationResult -> com.vaadin.flow.data.binder.ValidationResult
        com.vaadin.data.ValueContext -> com.vaadin.flow.data.binder.ValueContext
        com.vaadin.data.Result -> com.vaadin.flow.data.binder.Result
        com.vaadin.data.Validator -> com.vaadin.flow.data.binder.Validator
        com.vaadin.data.Converter -> com.vaadin.flow.data.converter.Converter
        com.vaadin.data.StatusChangeEvent -> com.vaadin.flow.data.binder.StatusChangeEvent
        com.vaadin.data.ErrorMessageProvider -> com.vaadin.flow.data.binder.ErrorMessageProvider
        com.vaadin.data.BeanPropertySet -> com.vaadin.flow.data.binder.BeanPropertySet
        com.vaadin.data.HasValue -> com.vaadin.flow.component.HasValue
        com.vaadin.data.ValueProvider -> com.vaadin.flow.function.ValueProvider
        com.vaadin.data.TreeData -> com.vaadin.flow.data.provider.hierarchy.TreeData
        com.vaadin.data.Item -> - ; V7-Datenmodell (Item) existiert nicht mehr – Beans + Binder/DataProvider verwenden
        com.vaadin.data.Property -> - ; V7-Datenmodell (Property) existiert nicht mehr – HasValue/Binder verwenden
        com.vaadin.data.Container -> - ; V7-Datenmodell (Container) existiert nicht mehr – DataProvider/setItems verwenden
        com.vaadin.data.provider.ListDataProvider -> com.vaadin.flow.data.provider.ListDataProvider
        com.vaadin.data.provider.DataProvider -> com.vaadin.flow.data.provider.DataProvider
        com.vaadin.data.provider.CallbackDataProvider -> com.vaadin.flow.data.provider.CallbackDataProvider
        com.vaadin.data.provider.AbstractBackEndDataProvider -> com.vaadin.flow.data.provider.AbstractBackEndDataProvider
        com.vaadin.data.provider.ConfigurableFilterDataProvider -> com.vaadin.flow.data.provider.ConfigurableFilterDataProvider
        com.vaadin.data.provider.Query -> com.vaadin.flow.data.provider.Query
        com.vaadin.data.provider.QuerySortOrder -> com.vaadin.flow.data.provider.QuerySortOrder
        com.vaadin.data.provider.SortOrder -> com.vaadin.flow.data.provider.SortOrder
        com.vaadin.data.provider.GridSortOrder -> com.vaadin.flow.component.grid.GridSortOrder
        com.vaadin.data.provider.TreeDataProvider -> com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
        com.vaadin.data.provider.HierarchicalQuery -> com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery
        com.vaadin.data.provider.AbstractBackEndHierarchicalDataProvider -> com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider
        com.vaadin.shared.data.sort.SortDirection -> com.vaadin.flow.data.provider.SortDirection
        com.vaadin.server.VaadinRequest -> com.vaadin.flow.server.VaadinRequest
        com.vaadin.server.VaadinResponse -> com.vaadin.flow.server.VaadinResponse
        com.vaadin.server.VaadinSession -> com.vaadin.flow.server.VaadinSession
        com.vaadin.server.VaadinService -> com.vaadin.flow.server.VaadinService
        com.vaadin.server.VaadinServlet -> com.vaadin.flow.server.VaadinServlet
        com.vaadin.server.VaadinServletRequest -> com.vaadin.flow.server.VaadinServletRequest
        com.vaadin.server.VaadinServletResponse -> com.vaadin.flow.server.VaadinServletResponse
        com.vaadin.server.VaadinServletService -> com.vaadin.flow.server.VaadinServletService
        com.vaadin.server.WrappedSession -> com.vaadin.flow.server.WrappedSession
        com.vaadin.server.WebBrowser -> com.vaadin.flow.server.WebBrowser
        com.vaadin.server.ErrorHandler -> com.vaadin.flow.server.ErrorHandler
        com.vaadin.server.ErrorEvent -> com.vaadin.flow.server.ErrorEvent
        com.vaadin.server.DefaultErrorHandler -> com.vaadin.flow.server.DefaultErrorHandler ; DefaultErrorHandler: doDefault(...) existiert nicht – Fehlerbehandlung neu schreiben
        com.vaadin.server.SessionInitListener -> com.vaadin.flow.server.SessionInitListener ; Registrierung über VaadinServiceInitListener
        com.vaadin.server.SessionDestroyListener -> com.vaadin.flow.server.SessionDestroyListener ; Registrierung über VaadinServiceInitListener
        com.vaadin.server.SessionInitEvent -> com.vaadin.flow.server.SessionInitEvent
        com.vaadin.server.SessionDestroyEvent -> com.vaadin.flow.server.SessionDestroyEvent
        com.vaadin.server.ServiceException -> com.vaadin.flow.server.ServiceException
        com.vaadin.server.SystemMessagesProvider -> com.vaadin.flow.server.SystemMessagesProvider
        com.vaadin.server.StreamResource -> com.vaadin.flow.server.StreamResource ; StreamResource ist veraltet – DownloadHandler (seit 24.8) verwenden
        com.vaadin.server.Page -> com.vaadin.flow.component.page.Page
        com.vaadin.server.SerializableFunction -> com.vaadin.flow.function.SerializableFunction
        com.vaadin.server.SerializablePredicate -> com.vaadin.flow.function.SerializablePredicate
        com.vaadin.server.SerializableConsumer -> com.vaadin.flow.function.SerializableConsumer
        com.vaadin.server.SerializableSupplier -> com.vaadin.flow.function.SerializableSupplier
        com.vaadin.server.SerializableComparator -> com.vaadin.flow.function.SerializableComparator
        com.vaadin.server.SerializableBiFunction -> com.vaadin.flow.function.SerializableBiFunction
        com.vaadin.server.SerializableBiConsumer -> com.vaadin.flow.function.SerializableBiConsumer
        com.vaadin.server.SerializableRunnable -> com.vaadin.flow.function.SerializableRunnable
        com.vaadin.server.Setter -> com.vaadin.flow.data.binder.Setter
        com.vaadin.server.VaadinIcons -> com.vaadin.flow.component.icon.VaadinIcon
        com.vaadin.icons.VaadinIcons -> com.vaadin.flow.component.icon.VaadinIcon
        com.vaadin.server.FontAwesome -> com.vaadin.flow.component.icon.VaadinIcon
        com.vaadin.server.Resource -> - ; Resource-API entfällt: URL-String, DownloadHandler oder Icon-Komponente verwenden
        com.vaadin.server.ThemeResource -> - ; ThemeResource: Datei nach src/main/resources/META-INF/resources/ verschieben und als URL referenzieren
        com.vaadin.server.ExternalResource -> - ; ExternalResource: direkt die URL als String verwenden
        com.vaadin.server.ClassResource -> - ; ClassResource: DownloadHandler.forClassResource(...) verwenden
        com.vaadin.server.FileResource -> - ; FileResource: DownloadHandler.forFile(...) verwenden
        com.vaadin.server.UserError -> - ; UserError: setInvalid(true) + setErrorMessage(...) verwenden
        com.vaadin.server.Sizeable -> -
        com.vaadin.server.Sizeable.Unit -> com.vaadin.flow.component.Unit
        com.vaadin.server.BootstrapListener -> - ; BootstrapListener: AppShellConfigurator#configurePage bzw. IndexHtmlRequestListener verwenden
        com.vaadin.shared.ui.ValueChangeMode -> com.vaadin.flow.data.value.ValueChangeMode
        com.vaadin.shared.ui.ContentMode -> -
        com.vaadin.shared.ui.label.ContentMode -> -
        com.vaadin.shared.ui.MarginInfo -> - ; MarginInfo: einzelne Seiten per CSS-Padding, sonst setPadding(boolean)
        com.vaadin.shared.ui.grid.HeightMode -> - ; HeightMode: Grid#setAllRowsVisible(true) oder feste Höhe
        com.vaadin.shared.ui.datefield.DateResolution -> - ; DateResolution: DatePicker ist tagesgenau, für Uhrzeit DateTimePicker/TimePicker
        com.vaadin.shared.Position -> -
        com.vaadin.shared.MouseEventDetails -> - ; MouseEventDetails: Details liefern die Flow-Events (getClickCount, isCtrlKey, ...)
        com.vaadin.shared.communication.PushMode -> com.vaadin.flow.shared.communication.PushMode
        com.vaadin.shared.ui.ui.Transport -> com.vaadin.flow.shared.ui.Transport
        com.vaadin.event.ShortcutAction -> -
        com.vaadin.event.ShortcutListener -> - ; ShortcutListener: Shortcuts.addShortcutListener(...) bzw. addClickShortcut verwenden
        com.vaadin.event.LayoutEvents -> - ; LayoutClickListener: addClickListener (ClickNotifier) bzw. getElement().addEventListener("click", ...)
        com.vaadin.event.FieldEvents -> - ; Focus/Blur: addFocusListener/addBlurListener der Komponente verwenden
        com.vaadin.event.Action -> - ; Action-Handler: Shortcuts.addShortcutListener bzw. ContextMenu verwenden
        com.vaadin.event.MouseEvents -> - ; MouseEvents: ClickEvent der jeweiligen Komponente verwenden
        com.vaadin.navigator.View -> -
        com.vaadin.navigator.Navigator -> - ; Navigator entfällt: Views per @Route registrieren, Navigation mit UI.getCurrent().navigate(...)
        com.vaadin.navigator.ViewChangeListener -> -
        com.vaadin.navigator.ViewDisplay -> - ; ViewDisplay: RouterLayout implementieren und @Route(layout = ...) verwenden
        com.vaadin.navigator.ViewProvider -> - ; ViewProvider: Routen per @Route oder RouteConfiguration registrieren
        com.vaadin.navigator.ViewBeforeLeaveEvent -> - ; BeforeLeaveObserver/BeforeLeaveEvent verwenden
        com.vaadin.annotations.Title -> com.vaadin.flow.router.PageTitle
        com.vaadin.annotations.Theme -> -
        com.vaadin.annotations.Push -> -
        com.vaadin.annotations.Widgetset -> -
        com.vaadin.annotations.VaadinServletConfiguration -> -
        com.vaadin.annotations.PreserveOnRefresh -> com.vaadin.flow.router.PreserveOnRefresh
        com.vaadin.annotations.Viewport -> com.vaadin.flow.component.page.Viewport ; @Viewport gehört an die AppShell-Klasse
        com.vaadin.annotations.StyleSheet -> com.vaadin.flow.component.dependency.StyleSheet ; @StyleSheet-Pfad prüfen: vaadin:// und theme:// gibt es nicht mehr, Dateien in META-INF/resources ablegen
        com.vaadin.annotations.JavaScript -> com.vaadin.flow.component.dependency.JavaScript ; @JavaScript-Pfad prüfen (META-INF/resources bzw. src/main/frontend)
        com.vaadin.annotations.DesignRoot -> - ; Declarative Designs werden nicht unterstützt – UI in Java nachbauen
        com.vaadin.annotations.PropertyId -> com.vaadin.flow.data.binder.PropertyId
        com.vaadin.spring.annotation.SpringUI -> -
        com.vaadin.spring.annotation.SpringView -> -
        com.vaadin.spring.annotation.SpringViewDisplay -> -
        com.vaadin.spring.annotation.SpringComponent -> com.vaadin.flow.spring.annotation.SpringComponent
        com.vaadin.spring.annotation.UIScope -> com.vaadin.flow.spring.annotation.UIScope
        com.vaadin.spring.annotation.VaadinSessionScope -> com.vaadin.flow.spring.annotation.VaadinSessionScope
        com.vaadin.spring.annotation.ViewScope -> com.vaadin.flow.spring.annotation.RouteScope ; @ViewScope -> @RouteScope: ggf. @RouteScopeOwner ergänzen
        com.vaadin.spring.annotation.EnableVaadin -> com.vaadin.flow.spring.annotation.EnableVaadin ; Mit Spring Boot ist @EnableVaadin meist unnötig
        com.vaadin.spring.navigator.SpringNavigator -> - ; SpringNavigator entfällt: Routing über @Route
        com.vaadin.spring.server.SpringVaadinServlet -> - ; SpringVaadinServlet entfällt: Spring Boot registriert das Servlet automatisch
        """;

    /** Ganze Pakete, deren Klassennamen gleich geblieben sind. */
    static final Map<String, String> PACKAGE_MAP = Map.of(
        "com.vaadin.data.converter", "com.vaadin.flow.data.converter",
        "com.vaadin.data.validator", "com.vaadin.flow.data.validator",
        "com.vaadin.event.selection", "com.vaadin.flow.data.selection",
        "com.vaadin.data.provider", "com.vaadin.flow.data.provider");

    /** Imports innerer Klassen mit Sonderbehandlung ("" = Import entfällt, Code wird transformiert). */
    static final Map<String, String> NESTED_SPECIAL = Map.ofEntries(
        entry("com.vaadin.ui.MenuBar.MenuItem", "com.vaadin.flow.component.contextmenu.MenuItem"),
        entry("com.vaadin.ui.MenuBar.Command", ""),
        entry("com.vaadin.ui.TabSheet.Tab", "com.vaadin.flow.component.tabs.Tab"),
        entry("com.vaadin.ui.TabSheet.SelectedTabChangeEvent", ""),
        entry("com.vaadin.ui.TabSheet.SelectedTabChangeListener", ""),
        entry("com.vaadin.ui.Component.Focusable", "com.vaadin.flow.component.Focusable"),
        entry("com.vaadin.ui.Button.ClickEvent", ""),
        entry("com.vaadin.ui.Button.ClickListener", ""),
        entry("com.vaadin.ui.Notification.Type", ""),
        entry("com.vaadin.ui.Window.CloseEvent", ""),
        entry("com.vaadin.ui.Window.CloseListener", ""),
        entry("com.vaadin.ui.Upload.Receiver", ""),
        entry("com.vaadin.ui.Upload.SucceededEvent", ""),
        entry("com.vaadin.ui.Upload.SucceededListener", ""),
        entry("com.vaadin.ui.Upload.FailedEvent", ""),
        entry("com.vaadin.ui.Upload.FailedListener", ""),
        entry("com.vaadin.ui.Upload.StartedEvent", ""),
        entry("com.vaadin.ui.Upload.StartedListener", ""),
        entry("com.vaadin.navigator.ViewChangeListener.ViewChangeEvent", ""),
        entry("com.vaadin.event.ShortcutAction.KeyCode", ""),
        entry("com.vaadin.event.ShortcutAction.ModifierKey", ""),
        entry("com.vaadin.server.Sizeable.Unit", "com.vaadin.flow.component.Unit"));

    /** Typen, die von Transformationen neu in den Code eingefügt werden. */
    static final Map<String, String> INTRODUCED = Map.ofEntries(
        entry("Div", "com.vaadin.flow.component.html.Div"),
        entry("Span", "com.vaadin.flow.component.html.Span"),
        entry("H3", "com.vaadin.flow.component.html.H3"),
        entry("Anchor", "com.vaadin.flow.component.html.Anchor"),
        entry("Image", "com.vaadin.flow.component.html.Image"),
        entry("IFrame", "com.vaadin.flow.component.html.IFrame"),
        entry("Button", "com.vaadin.flow.component.button.Button"),
        entry("Dialog", "com.vaadin.flow.component.dialog.Dialog"),
        entry("VerticalLayout", "com.vaadin.flow.component.orderedlayout.VerticalLayout"),
        entry("FlexComponent", "com.vaadin.flow.component.orderedlayout.FlexComponent"),
        entry("SplitLayout", "com.vaadin.flow.component.splitlayout.SplitLayout"),
        entry("UI", "com.vaadin.flow.component.UI"),
        entry("Composite", "com.vaadin.flow.component.Composite"),
        entry("ClickEvent", "com.vaadin.flow.component.ClickEvent"),
        entry("ComponentEventListener", "com.vaadin.flow.component.ComponentEventListener"),
        entry("AttachEvent", "com.vaadin.flow.component.AttachEvent"),
        entry("DetachEvent", "com.vaadin.flow.component.DetachEvent"),
        entry("Key", "com.vaadin.flow.component.Key"),
        entry("KeyModifier", "com.vaadin.flow.component.KeyModifier"),
        entry("Unit", "com.vaadin.flow.component.Unit"),
        entry("Route", "com.vaadin.flow.router.Route"),
        entry("BeforeEnterEvent", "com.vaadin.flow.router.BeforeEnterEvent"),
        entry("BeforeEnterObserver", "com.vaadin.flow.router.BeforeEnterObserver"),
        entry("VaadinSession", "com.vaadin.flow.server.VaadinSession"),
        entry("VaadinRequest", "com.vaadin.flow.server.VaadinRequest"),
        entry("VaadinIcon", "com.vaadin.flow.component.icon.VaadinIcon"),
        entry("MenuItem", "com.vaadin.flow.component.contextmenu.MenuItem"),
        entry("Notification", "com.vaadin.flow.component.notification.Notification"),
        entry("NotificationVariant", "com.vaadin.flow.component.notification.NotificationVariant"),
        entry("ButtonVariant", "com.vaadin.flow.component.button.ButtonVariant"),
        entry("TextFieldVariant", "com.vaadin.flow.component.textfield.TextFieldVariant"),
        entry("TextAreaVariant", "com.vaadin.flow.component.textfield.TextAreaVariant"),
        entry("ComboBoxVariant", "com.vaadin.flow.component.combobox.ComboBoxVariant"),
        entry("DatePickerVariant", "com.vaadin.flow.component.datepicker.DatePickerVariant"),
        entry("GridVariant", "com.vaadin.flow.component.grid.GridVariant"));

    /** ValoTheme-Konstanten mit Lumo-Pendant (Theme-Variante). */
    static final Map<String, String> VALO = Map.ofEntries(
        entry("BUTTON_PRIMARY", "ButtonVariant.LUMO_PRIMARY"),
        entry("BUTTON_FRIENDLY", "ButtonVariant.LUMO_SUCCESS"),
        entry("BUTTON_DANGER", "ButtonVariant.LUMO_ERROR"),
        entry("BUTTON_SMALL", "ButtonVariant.LUMO_SMALL"),
        entry("BUTTON_TINY", "ButtonVariant.LUMO_SMALL"),
        entry("BUTTON_LARGE", "ButtonVariant.LUMO_LARGE"),
        entry("BUTTON_HUGE", "ButtonVariant.LUMO_LARGE"),
        entry("BUTTON_BORDERLESS", "ButtonVariant.LUMO_TERTIARY"),
        entry("BUTTON_BORDERLESS_COLORED", "ButtonVariant.LUMO_TERTIARY"),
        entry("BUTTON_QUIET", "ButtonVariant.LUMO_TERTIARY"),
        entry("BUTTON_LINK", "ButtonVariant.LUMO_TERTIARY_INLINE"),
        entry("BUTTON_ICON_ONLY", "ButtonVariant.LUMO_ICON"),
        entry("TEXTFIELD_SMALL", "TextFieldVariant.LUMO_SMALL"),
        entry("TEXTFIELD_ALIGN_RIGHT", "TextFieldVariant.LUMO_ALIGN_RIGHT"),
        entry("TEXTFIELD_ALIGN_CENTER", "TextFieldVariant.LUMO_ALIGN_CENTER"),
        entry("TEXTAREA_SMALL", "TextAreaVariant.LUMO_SMALL"),
        entry("COMBOBOX_SMALL", "ComboBoxVariant.LUMO_SMALL"),
        entry("DATEFIELD_SMALL", "DatePickerVariant.LUMO_SMALL"),
        entry("TABLE_COMPACT", "GridVariant.LUMO_COMPACT"),
        entry("TABLE_SMALL", "GridVariant.LUMO_COMPACT"),
        entry("TABLE_NO_HORIZONTAL_LINES", "GridVariant.LUMO_NO_ROW_BORDERS"),
        entry("TABLE_BORDERLESS", "GridVariant.LUMO_NO_BORDER"));

    record TypeMapping(String oldSimple, List<String> oldFqns, String newFqn, String note) {
        String newSimple() { return newFqn == null ? null : newFqn.substring(newFqn.lastIndexOf('.') + 1); }
        boolean removed() { return newFqn == null; }
        String oldPackage(String fqn) { return fqn.substring(0, fqn.lastIndexOf('.')); }
    }

    static final List<TypeMapping> MAPPINGS = new ArrayList<>();
    static final Map<String, TypeMapping> BY_OLD_FQN = new HashMap<>();

    static {
        for (String raw : TYPE_TABLE.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String note = null;
            int semi = line.indexOf(" ; ");
            if (semi >= 0) {
                note = line.substring(semi + 3).strip();
                line = line.substring(0, semi).strip();
            }
            String[] parts = line.split("\\s*->\\s*");
            String oldFqn = parts[0].strip();
            String newFqn = parts[1].strip().equals("-") ? null : parts[1].strip();
            List<String> olds = new ArrayList<>(List.of(oldFqn));
            if (oldFqn.startsWith("com.vaadin.ui.")) olds.add("com.vaadin.v7.ui." + oldFqn.substring("com.vaadin.ui.".length()));
            if (oldFqn.startsWith("com.vaadin.data.")) olds.add("com.vaadin.v7.data." + oldFqn.substring("com.vaadin.data.".length()));
            TypeMapping tm = new TypeMapping(oldFqn.substring(oldFqn.lastIndexOf('.') + 1), List.copyOf(olds), newFqn, note);
            MAPPINGS.add(tm);
            for (String o : olds) BY_OLD_FQN.put(o, tm);
        }
    }

    // Typgruppen (alte Namen) für typabhängige Umschreibungen
    static final Set<String> LABELED = Set.of("TextField", "TextArea", "PasswordField", "ComboBox", "CheckBox", "DateField",
        "DateTimeField", "InlineDateField", "InlineDateTimeField", "NativeSelect", "CheckBoxGroup", "RadioButtonGroup",
        "TwinColSelect", "OptionGroup");
    static final Set<String> TEXTUAL = Set.of("Button", "Link");
    static final Set<String> CAPTIONLESS = Set.of("VerticalLayout", "HorizontalLayout", "FormLayout", "CssLayout", "GridLayout",
        "AbsoluteLayout", "Panel", "TabSheet", "Accordion", "HorizontalSplitPanel", "VerticalSplitPanel", "Grid", "TreeGrid",
        "Tree", "Table", "Image", "Upload", "ListSelect", "Label", "ProgressBar", "RichTextArea", "MenuBar", "Slider",
        "Embedded", "BrowserFrame");
    static final Set<String> TOOLTIP = union(LABELED, Set.of("Button"));
    static final Set<String> FIELDS = union(LABELED, Set.of("ListSelect", "RichTextArea", "Slider"));
    static final Set<String> ORDERED = Set.of("VerticalLayout", "HorizontalLayout", "FormLayout", "CssLayout");
    static final Set<String> FLEX = Set.of("VerticalLayout", "HorizontalLayout");
    static final Set<String> SELECTS = Set.of("Grid", "TreeGrid", "ComboBox", "NativeSelect", "ListSelect", "CheckBoxGroup",
        "RadioButtonGroup", "TwinColSelect");
    static final Set<String> GRIDS = Set.of("Grid", "TreeGrid");
    static final Set<String> DATES = Set.of("DateField", "InlineDateField", "DateTimeField", "InlineDateTimeField");
    static final Set<String> SPLITS = Set.of("HorizontalSplitPanel", "VerticalSplitPanel");

    record TypedRule(Set<String> types, String method, String replacement, String todo) {}

    static final List<TypedRule> TYPED_RULES = List.of(
        new TypedRule(LABELED, "setCaption", "setLabel", null),
        new TypedRule(LABELED, "getCaption", "getLabel", null),
        new TypedRule(TEXTUAL, "setCaption", "setText", null),
        new TypedRule(TEXTUAL, "getCaption", "getText", null),
        new TypedRule(Set.of("Window"), "setCaption", "setHeaderTitle", null),
        new TypedRule(Set.of("Window"), "getCaption", "getHeaderTitle", null),
        new TypedRule(Set.of("Label"), "setValue", "setText", null),
        new TypedRule(Set.of("Label"), "getValue", "getText", null),
        new TypedRule(CAPTIONLESS, "setCaption", null, "Caption gibt es in Flow nicht – Überschrift (z.B. H3/Span) davor setzen oder FormLayout#addFormItem verwenden"),
        new TypedRule(TOOLTIP, "setDescription", "setTooltipText", null),
        new TypedRule(CAPTIONLESS, "setDescription", null, "Tooltip: Tooltip.forComponent(komponente).setText(...) verwenden"),
        new TypedRule(Set.of("Panel", "Window", "UI"), "setContent", "add", null),
        new TypedRule(FLEX, "setMargin", "setPadding", null),
        new TypedRule(ORDERED, "getComponent", "getComponentAt", null),
        new TypedRule(SELECTS, "setDataProvider", "setItems", null),
        new TypedRule(Set.of("ComboBox"), "setEmptySelectionAllowed", "setClearButtonVisible", null),
        new TypedRule(DATES, "setRangeStart", "setMin", null),
        new TypedRule(DATES, "setRangeEnd", "setMax", null),
        new TypedRule(FIELDS, "setRequired", "setRequiredIndicatorVisible", null),
        new TypedRule(Set.of("TextField", "TextArea", "PasswordField", "ComboBox", "DateField"), "setIcon", "setPrefixComponent", null),
        new TypedRule(Set.of("Image"), "setAlternateText", "setAlt", null),
        new TypedRule(Set.of("Image"), "setSource", null, "Image: setSrc(url) bzw. setSrc(DownloadHandler) verwenden"),
        new TypedRule(Set.of("Link"), "setTargetName", "setTarget", null),
        new TypedRule(Set.of("Link"), "setResource", null, "Anchor: setHref(url) verwenden"),
        new TypedRule(SPLITS, "setFirstComponent", "addToPrimary", null),
        new TypedRule(SPLITS, "setSecondComponent", "addToSecondary", null),
        new TypedRule(SPLITS, "setSplitPosition", null, "SplitLayout#setSplitterPosition(double prozent) verwenden"),
        new TypedRule(Set.of("Notification"), "setDelayMsec", "setDuration", null),
        new TypedRule(Set.of("Label"), "setContentMode", null, "ContentMode: für HTML die Html-Komponente verwenden (XSS beachten)"),
        new TypedRule(GRIDS, "getColumn", "getColumnByKey", null),
        new TypedRule(GRIDS, "setStyleGenerator", "setClassNameGenerator", null));

    record LineRule(Pattern pattern, String msg) {}

    static LineRule rule(String regex, String msg) { return new LineRule(Pattern.compile(regex), msg); }

    /** Hinweise, die auf dem Originalcode (vor der Umschreibung) gesetzt werden. */
    static final List<LineRule> PRE_RULES = List.of(
        rule("\\b(BeanItemContainer|IndexedContainer|BeanItem|PropertysetItem|ObjectProperty|MethodProperty|FieldGroup|BeanFieldGroup|SQLContainer|HierarchicalContainer)\\b",
            "V7-Datenmodell (Container/Item/Property/FieldGroup) existiert nicht mehr – Beans mit setItems/DataProvider und Binder verwenden"),
        rule("\\bgetProperty\\s*\\(\\s*\\)", "V7-Property-API: event.getValue() bzw. HasValue verwenden"),
        rule("\\.\\s*addView\\s*\\(", "Navigator#addView entfällt – Route per @Route an der View-Klasse"),
        rule("\\bgetNavigator\\s*\\(\\s*\\)(?!\\s*\\.\\s*navigateTo)", "Navigator-Zugriff: Router-API (UI.navigate, RouteConfiguration, BeforeEnterObserver) verwenden"),
        rule("\\bMarginInfo\\b", "setMargin(MarginInfo): einzelne Seiten per CSS-Padding, sonst setPadding(boolean)"),
        rule("\\bContentMode\\s*\\.\\s*(HTML|PREFORMATTED)\\b", "HTML/PRE-Inhalt: Html- bzw. Pre-Komponente verwenden (XSS beachten)"),
        rule("\\bsetHeightByRows\\b|\\bsetHeightMode\\b", "Grid-Höhe: setAllRowsVisible(true) oder feste Höhe setzen"),
        rule("\\bgetEditor\\s*\\(\\s*\\)", "Grid-Editor: Editor-Komponenten per Column#setEditorComponent und Binder einrichten"),
        rule("\\bgetMouseEventDetails\\b", "Doppelklick: addItemDoubleClickListener bzw. event.getClickCount() verwenden"),
        rule("\\baddSelectedTabChangeListener\\b", "TabSheet: addSelectedChangeListener verwenden"),
        rule("\\bsetSelectedTab\\b", "TabSheet: setSelectedTab(Tab) bzw. setSelectedIndex(int) verwenden"),
        rule("\\bsetClosable\\b", "Dialog hat keinen Schließen-Button: z.B. dialog.getHeader().add(closeButton); setCloseOnEsc/setCloseOnOutsideClick"),
        rule("\\baddCloseListener\\b", "Dialog: addOpenedChangeListener bzw. addDialogCloseActionListener verwenden"),
        rule("\\bsetWindowMode\\b", "Dialog: Maximieren nicht direkt unterstützt (setWidthFull/setHeightFull)"),
        rule("\\bsetPositionX\\b|\\bsetPositionY\\b", "Dialog-Position: setTop/setLeft verwenden"),
        rule("\\bgetParameters\\s*\\(\\s*\\)", "URL-Parameter: HasUrlParameter<String> oder event.getRouteParameters() verwenden"),
        rule("\\b(set|get)UriFragment\\b", "URI-Fragmente: Router-Parameter bzw. Page#fetchCurrentURL verwenden"),
        rule("\\bgetBrowserWindow(Width|Height)\\b", "Fenstergröße: Page#retrieveExtendedClientDetails bzw. addBrowserWindowResizeListener"),
        rule("\\bsetComponentError\\b", "Fehleranzeige: setInvalid(true) + setErrorMessage(...) verwenden"),
        rule("\\bsetRequiredError\\b", "Pflichtfeld-Meldung: binder.forField(...).asRequired(\"...\") verwenden"),
        rule("\\baddShortcutListener\\b", "Shortcut: Shortcuts.addShortcutListener(...) bzw. addClickShortcut verwenden"),
        rule("\\baddLayoutClickListener\\b", "Klick auf Layout: addClickListener bzw. getElement().addEventListener(\"click\", ...)"),
        rule("\\bsetDateFormat\\b", "Datumsformat: DatePickerI18n#setDateFormat verwenden"),
        rule("\\bsetResolution\\b", "DatePicker ist tagesgenau – für Uhrzeit DateTimePicker/TimePicker verwenden"),
        rule("\\bsetTextInputAllowed\\b", "ComboBox: Eingabe dient als Filter – ggf. Select verwenden"),
        rule("\\bsetPageLength\\b", "setPageLength: kein direktes Pendant (setPageSize ist die Lazy-Loading-Seitengröße)"),
        rule("\\bsetNewItem(Provider|Handler)\\b", "Neue Einträge: setAllowCustomValue(true) + addCustomValueSetListener"),
        rule("\\bsetRows\\s*\\(", "TextArea: setMinRows/setMaxRows bzw. Höhe per CSS"),
        rule("\\bsetWordWrap\\b", "setWordWrap entfällt"),
        rule("\\bset(Cell|Row)StyleGenerator\\b", "Grid: setPartNameGenerator bzw. setClassNameGenerator verwenden"),
        rule("\\bsetDescriptionGenerator\\b", "Tooltips im Grid: Column#setTooltipGenerator"),
        rule("\\bsetFrozenColumnCount\\b", "Fixierte Spalten: Column#setFrozen(true)"),
        rule("\\bsetItemIconGenerator\\b", "Icons in Einträgen: setRenderer(new ComponentRenderer<>(...))"),
        rule("\\bDesign\\s*\\.\\s*read\\b", "Declarative Design wird nicht unterstützt – UI in Java nachbauen"),
        rule("\\bservletInitialized\\b", "Initialisierung: VaadinServiceInitListener (Spring-Bean oder META-INF/services) verwenden"),
        rule("\\bmodifyBootstrap(Page|Fragment)\\b", "Bootstrap-Anpassung: AppShellConfigurator#configurePage"),
        rule("\\bMenuBar\\s*\\.\\s*Command\\b|\\bnew\\s+Command\\s*\\(", "MenuBar: addItem(text, e -> ...) – Listener erhält ClickEvent<MenuItem>"),
        rule("\\bmarkAsDirty\\b", "markAsDirty entfällt"),
        rule("\\bgetConnectorId\\b", "getConnectorId entfällt – ggf. setId/getId verwenden"),
        rule("\\bgetStreamVariable\\b|\\bStreamVariable\\b", "StreamVariable entfällt – UploadHandler verwenden"),
        rule("\\bsetIcon\\s*\\(\\s*new\\s+(Theme|External|Class|File)Resource\\b", "Icon aus Datei: new Image(url, alt) bzw. SvgIcon verwenden"),
        rule("\\bgetSelectedRow\\b|\\bsetSelectable\\b|\\bsetEditable\\b", "V7-Table-API: Grid-Auswahl/-Editor verwenden"),
        rule("\\bsetValue\\s*\\(\\s*new\\s+(java\\.util\\.)?Date\\s*\\(", "Datum: Flow-Felder verwenden LocalDate/LocalDateTime"));

    // ------------------------------------------------------------------------------------------------
    // Programm
    // ------------------------------------------------------------------------------------------------

    final Path src, dst;
    final boolean dryRun, singleFile;
    final List<FileReport> reports = new ArrayList<>();
    final List<String> globalNotes = new ArrayList<>();
    final AppShellInfo appShell = new AppShellInfo();
    int javaFiles, vaadinFiles, copied;

    record FileReport(Path rel, boolean vaadin, List<String> todos) {}

    static final class AppShellInfo {
        Text source = new Text("", StandardCharsets.UTF_8, false);
        Path dir;
        String pkg;
        boolean needed, push;
        String pushArgs = "", theme;
        final List<String> sources = new ArrayList<>();
    }

    VaadinMigrator(Path src, Path dst, boolean dryRun) {
        this.src = src;
        this.dst = dst;
        this.dryRun = dryRun;
        this.singleFile = Files.isRegularFile(src);
    }

    public static void main(String[] args) throws IOException {
        List<String> pos = new ArrayList<>();
        boolean dryRun = false;
        for (String a : args) {
            if (a.startsWith("--vaadin-version=")) targetVersion = a.substring("--vaadin-version=".length());
            else if (a.startsWith("--encoding=")) forcedInput = charsetArg(a.substring("--encoding=".length()));
            else if (a.startsWith("--output-encoding=")) forcedOutput = charsetArg(a.substring("--output-encoding=".length()));
            else if (a.equals("--dry-run")) dryRun = true;
            else if (a.equals("-h") || a.equals("--help")) { usage(); return; }
            else pos.add(a);
        }
        if (pos.size() != 2) { usage(); System.exit(1); }
        Path src = Path.of(pos.get(0)).toAbsolutePath().normalize();
        Path dst = Path.of(pos.get(1)).toAbsolutePath().normalize();
        if (!Files.exists(src)) { System.err.println("Quelle existiert nicht: " + src); System.exit(2); }
        if (Files.isDirectory(src) && dst.startsWith(src)) { System.err.println("Das Ziel darf nicht im Quellverzeichnis liegen."); System.exit(2); }
        if (src.equals(dst)) { System.err.println("Quelle und Ziel müssen verschieden sein."); System.exit(2); }
        new VaadinMigrator(src, dst, dryRun).run();
    }

    static Charset charsetArg(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            System.err.println("Unbekannte Zeichenkodierung: " + name);
            System.exit(2);
            return null;
        }
    }

    static void usage() {
        System.out.println("""
            VaadinMigrator – Vaadin 7/8 -> Vaadin 25 (Flow)

            Aufruf:
              java VaadinMigrator.java <quelle> <ziel> [--vaadin-version=25.3.0] [--dry-run]

              <quelle>   Projektverzeichnis (wird nicht verändert) oder einzelne .java-Datei
              <ziel>     neues Verzeichnis bzw. Zieldatei
              --dry-run  nichts schreiben, nur Bericht auf der Konsole
              --encoding=<zs>         Eingabekodierung erzwingen (Standard: automatisch
                                      UTF-8 -> windows-1252 -> ISO-8859-1)
              --output-encoding=<zs>  alle konvertierten Dateien in dieser Kodierung schreiben
                                      (z.B. UTF-8; Standard: Kodierung der Quelldatei beibehalten)
            """);
    }

    void run() throws IOException {
        if (singleFile) {
            processFile(src, dst, src.getFileName());
        } else {
            List<Path> files;
            try (Stream<Path> s = Files.walk(src)) {
                files = s.filter(Files::isRegularFile).filter(p -> !skipped(src.relativize(p))).sorted().toList();
            }
            for (Path f : files) {
                Path rel = src.relativize(f);
                processFile(f, dst.resolve(rel), rel);
            }
            Files.walk(src).filter(Files::isDirectory)
                .filter(d -> d.endsWith(Path.of("VAADIN", "themes")) && !skipped(src.relativize(d))).findFirst()
                .ifPresent(d -> globalNotes.add("Valo-SCSS-Themes unter `" + src.relativize(d) + "` werden nicht konvertiert. "
                    + "Styles als CSS nach `src/main/resources/META-INF/resources/` (oder `src/main/frontend/`) portieren und per `@StyleSheet` in der AppShell einbinden."));
        }
        writeAppShell();
        String report = report();
        if (dryRun) {
            System.out.println(report);
        } else {
            Path reportFile = singleFile ? dst.resolveSibling("MIGRATION_REPORT.md") : dst.resolve("MIGRATION_REPORT.md");
            write(reportFile, report, StandardCharsets.UTF_8);
            System.out.println("Fertig. Bericht: " + reportFile);
        }
        int todos = reports.stream().mapToInt(r -> r.todos().size()).sum();
        System.out.printf("Java-Dateien: %d, davon mit Vaadin-Bezug: %d, TODO-Markierungen: %d, sonstige Dateien kopiert: %d%n",
            javaFiles, vaadinFiles, todos, copied);
    }

    static boolean skipped(Path rel) {
        for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return true;
        return false;
    }

    void processFile(Path in, Path out, Path rel) throws IOException {
        String name = in.getFileName().toString();
        if (name.endsWith(".java")) {
            javaFiles++;
            Text src = readText(in, rel);
            String text = src.content();
            JavaFile jf = new JavaFile(rel, out);
            jf.source = src;
            String result;
            try {
                result = jf.convert(text);
            } catch (RuntimeException e) {
                globalNotes.add("Fehler beim Konvertieren von `" + rel + "` (" + e + ") – Datei unverändert kopiert.");
                result = text;
            }
            if (jf.vaadin) vaadinFiles++;
            reports.add(new FileReport(rel, jf.vaadin, todoLines(result)));
            writeText(out, result, src, rel, true);
        } else if (name.equals("pom.xml")) {
            Text pom = readText(in, rel);
            writeText(out, convertPom(pom.content(), rel), pom, rel, false);
        } else {
            if (name.endsWith(".gwt.xml")) globalNotes.add("`" + rel + "`: Widgetset-Definition wird in Flow nicht mehr benötigt – Datei löschen, Client-Add-ons durch Flow-Add-ons ersetzen.");
            if (name.equals("web.xml") && new String(Files.readAllBytes(in), StandardCharsets.ISO_8859_1).contains("com.vaadin"))
                globalNotes.add("`" + rel + "`: Vaadin-Servlet-Konfiguration prüfen – mit Spring Boot bzw. Servlet-3-Autoerkennung meist überflüssig, Namespace auf jakarta.ee umstellen.");
            if (name.equals("build.gradle") || name.equals("build.gradle.kts"))
                globalNotes.add("`" + rel + "`: Gradle-Build wird nicht automatisch angepasst – Vaadin-Plugin `com.vaadin` " + targetVersion + ", Abhängigkeit `com.vaadin:vaadin`, Java 21 setzen.");
            if (name.endsWith(".html") && new String(Files.readAllBytes(in), StandardCharsets.ISO_8859_1).matches("(?s).*<v-[a-z-]+.*"))
                globalNotes.add("`" + rel + "`: sieht nach einem Vaadin-8-Declarative-Design aus – muss in Java nachgebaut werden.");
            copied++;
            if (!dryRun) {
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Zeichenkodierung
    // ------------------------------------------------------------------------------------------------

    /** Dateiinhalt mit erkannter Kodierung und BOM-Kennzeichen. */
    record Text(String content, Charset charset, boolean bom) {}

    final Map<String, Integer> charsetStats = new TreeMap<>();
    final List<String> encodingNotes = new ArrayList<>();

    /** Liest strikt: UTF-8 (BOM wird erkannt und entfernt), sonst windows-1252, sonst ISO-8859-1. */
    Text readText(Path file, Path rel) throws IOException {
        byte[] b = Files.readAllBytes(file);
        boolean bom = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
        int off = bom ? 3 : 0;
        List<Charset> candidates = forcedInput != null
            ? List.of(forcedInput)
            : bom ? List.of(StandardCharsets.UTF_8) : List.of(StandardCharsets.UTF_8, WINDOWS_1252, StandardCharsets.ISO_8859_1);
        for (Charset cs : candidates) {
            try {
                String s = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
                // Reines ASCII ist in jeder gängigen Kodierung gültig -> beim Schreiben ASCII bleiben,
                // damit eingefügte Hinweise unabhängig von der Build-Kodierung (UTF-8/Cp1252/...) korrekt sind.
                if (forcedInput == null && !bom && cs.equals(StandardCharsets.UTF_8) && isAscii(b)) cs = StandardCharsets.US_ASCII;
                charsetStats.merge(cs.name(), 1, Integer::sum);
                return new Text(s, cs, bom);
            } catch (CharacterCodingException e) {
                // nächste Kandidatin versuchen
            }
        }
        Charset cs = candidates.get(0);
        encodingNotes.add("`" + rel + "`: nicht gültig in " + cs.name() + " – ungültige Bytes wurden durch U+FFFD ersetzt. Datei prüfen!");
        charsetStats.merge(cs.name() + " (fehlerhaft)", 1, Integer::sum);
        return new Text(new String(b, off, b.length - off, cs), cs, bom);
    }

    static boolean isAscii(byte[] b) {
        for (byte x : b) if (x < 0) return false;
        return true;
    }

    /** Typografische Zeichen, die u.a. in eigenen TODO-Texten vorkommen, auf ASCII abbilden. */
    static final Map<Character, String> TRANSLIT = Map.ofEntries(
        entry('\u2013', "-"), entry('\u2014', "-"), entry('\u2192', "->"), entry('\u2190', "<-"),
        entry('\u2026', "..."), entry('\u201E', "\""), entry('\u201C', "\""), entry('\u201D', "\""),
        entry('\u201A', "'"), entry('\u2018', "'"), entry('\u2019', "'"), entry('\u00A0', " "),
        entry('\u00E4', "ae"), entry('\u00F6', "oe"), entry('\u00FC', "ue"), entry('\u00C4', "Ae"),
        entry('\u00D6', "Oe"), entry('\u00DC', "Ue"), entry('\u00DF', "ss"));

    /**
     * Schreibt so, dass nie eine UnmappableCharacterException entsteht: Zeichen, die die Zielkodierung nicht kennt,
     * werden transliteriert; was dann noch übrig bleibt, wird in Java-Quellen als \\uXXXX, sonst als '?' geschrieben.
     */
    void writeText(Path out, String content, Text src, Path rel, boolean javaSource) throws IOException {
        Charset target = forcedOutput != null ? forcedOutput : src.charset();
        CharsetEncoder enc = target.newEncoder();
        String text = content;
        int escaped = 0;
        if (!enc.canEncode(text)) {
            StringBuilder sb = new StringBuilder(text.length() + 64);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isSurrogate(ch) && enc.canEncode(ch)) { sb.append(ch); continue; }
                String t = TRANSLIT.get(ch);
                if (t != null && enc.canEncode(t)) { sb.append(t); continue; }
                escaped++;
                sb.append(javaSource ? String.format("\\u%04X", (int) ch) : "?");
            }
            text = sb.toString();
        }
        if (escaped > 0)
            encodingNotes.add("`" + rel + "`: " + escaped + " Zeichen sind in " + target.name() + " nicht darstellbar und wurden "
                + (javaSource ? "als \\uXXXX-Escape" : "als '?'") + " geschrieben.");
        if (dryRun) return;
        ByteBuffer bytes = target.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(text));
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data);
        boolean bom = src.bom() && target.equals(StandardCharsets.UTF_8);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (var os = Files.newOutputStream(out)) {
            if (bom) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            os.write(data);
        }
    }

    void write(Path out, String content, Charset cs) throws IOException {
        if (dryRun) return;
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, content, cs);
    }

    static List<String> todoLines(String code) {
        List<String> res = new ArrayList<>();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int k = lines[i].indexOf(TODO_PREFIX);
            if (k >= 0) res.add((i + 1) + "\t" + lines[i].substring(k + TODO_PREFIX.length()).strip());
        }
        return res;
    }

    // ------------------------------------------------------------------------------------------------
    // pom.xml
    // ------------------------------------------------------------------------------------------------

    String convertPom(String xml, Path rel) {
        String p = xml.replace("\r\n", "\n");
        List<String> notes = new ArrayList<>();
        String before;

        before = p;
        p = p.replaceAll("(<vaadin\\.version>)[^<]*(</vaadin\\.version>)", "$1" + targetVersion + "$2");
        if (!p.equals(before)) notes.add("Property `vaadin.version` auf " + targetVersion + " gesetzt.");

        p = p.replaceAll("(<artifactId>vaadin-bom</artifactId>\\s*<version>)(?!\\$\\{)[^<]*(</version>)", "$1" + targetVersion + "$2");

        // Vaadin-8-Artefakte entfernen
        Pattern oldDep = Pattern.compile("(?s)([ \\t]*)<dependency>(?:(?!</dependency>).)*?<artifactId>(vaadin-server|vaadin-client|vaadin-client-compiled|"
            + "vaadin-client-compiler|vaadin-themes|vaadin-push|vaadin-shared|vaadin-compatibility-[\\w-]+)</artifactId>(?:(?!</dependency>).)*?</dependency>[ \\t]*\\n?");
        Matcher m = oldDep.matcher(p);
        StringBuilder sb = new StringBuilder();
        List<String> removed = new ArrayList<>();
        int firstPos = -1;
        String indent = "        ";
        while (m.find()) {
            m.appendReplacement(sb, "");
            if (firstPos < 0) { firstPos = sb.length(); indent = m.group(1); }
            removed.add(m.group(2));
        }
        m.appendTail(sb);
        p = sb.toString();
        if (!removed.isEmpty()) {
            notes.add("Entfernte Vaadin-8-Artefakte: " + String.join(", ", removed) + ".");
            boolean hasFlow = Pattern.compile("<artifactId>(vaadin|vaadin-core|vaadin-spring-boot-starter)</artifactId>").matcher(p).find();
            if (!hasFlow) {
                String dep = indent + "<dependency>\n" + indent + "    <groupId>com.vaadin</groupId>\n" + indent
                    + "    <artifactId>vaadin</artifactId>\n" + indent + "</dependency>\n";
                p = p.substring(0, firstPos) + dep + p.substring(firstPos);
                notes.add("Abhängigkeit `com.vaadin:vaadin` ergänzt (für nur freie Komponenten: `vaadin-core`).");
            }
        }
        boolean usesVaadin = p.contains("<groupId>com.vaadin</groupId>");
        if (usesVaadin && !p.contains("<artifactId>vaadin-bom</artifactId>")) {
            String bom = "            <dependency>\n                <groupId>com.vaadin</groupId>\n                <artifactId>vaadin-bom</artifactId>\n"
                + "                <version>" + targetVersion + "</version>\n                <type>pom</type>\n                <scope>import</scope>\n"
                + "            </dependency>\n";
            if (Pattern.compile("<dependencyManagement>\\s*<dependencies>").matcher(p).find()) {
                p = p.replaceFirst("(<dependencyManagement>\\s*<dependencies>)\\n?", "$1\n" + Matcher.quoteReplacement(bom));
            } else {
                p = p.replaceFirst("(\\n[ \\t]*<dependencies>)", Matcher.quoteReplacement("\n    <dependencyManagement>\n        <dependencies>\n" + bom
                    + "        </dependencies>\n    </dependencyManagement>") + "$1");
            }
            notes.add("`vaadin-bom` " + targetVersion + " in dependencyManagement ergänzt.");
        }
        before = p;
        p = p.replaceAll("(<artifactId>vaadin-spring(?:-boot-starter)?</artifactId>)\\s*<version>[^<]*</version>", "$1");
        if (!p.equals(before)) notes.add("Versionen von vaadin-spring(-boot-starter) entfernt (kommen aus der BOM).");

        before = p;
        p = p.replaceAll("<groupId>javax\\.servlet</groupId>(\\s*)<artifactId>javax\\.servlet-api</artifactId>",
            "<groupId>jakarta.servlet</groupId>$1<artifactId>jakarta.servlet-api</artifactId>");
        p = p.replaceAll("(<artifactId>jakarta\\.servlet-api</artifactId>\\s*<version>)[34][^<]*(</version>)", "$16.1.0$2");
        if (!p.equals(before)) notes.add("javax.servlet-api durch jakarta.servlet-api 6.1 ersetzt.");
        if (p.contains("<artifactId>validation-api</artifactId>") || p.contains("<groupId>javax.")) {
            notes.add("Weitere `javax.*`-Abhängigkeiten gefunden – auf `jakarta.*`-Artefakte umstellen (z.B. jakarta.validation-api).");
        }

        before = p;
        p = p.replaceAll("(<(maven\\.compiler\\.(?:source|target|release)|java\\.version)>)[^<]*(</\\2>)", "$121$3");
        p = p.replaceAll("(<(source|target|release)>)(1\\.[5-8]|[5-9]|1[0-9]|20)(</\\2>)", "$121$4");
        if (!p.equals(before)) notes.add("Java-Version auf 21 gesetzt.");

        Matcher vp = Pattern.compile("(?s)(<artifactId>vaadin-maven-plugin</artifactId>.*?<goals>)(.*?)(</goals>)").matcher(p);
        if (vp.find() && vp.group(2).matches("(?s).*<goal>(update-theme|update-widgetset|compile-theme|compile|resources|clean)</goal>.*")) {
            String ind = "                        ";
            p = p.substring(0, vp.start()) + vp.group(1) + "\n" + ind + "<goal>prepare-frontend</goal>\n" + ind.substring(4) + vp.group(3) + p.substring(vp.end());
            notes.add("vaadin-maven-plugin: Widgetset-/Theme-Goals durch `prepare-frontend` ersetzt – für Produktions-Builds `build-frontend` in einem production-Profil ergänzen; alte `<configuration>` des Plugins prüfen/entfernen.");
        }
        p = p.replaceAll("(<artifactId>vaadin-maven-plugin</artifactId>\\s*<version>)(?!\\$\\{)[^<]*(</version>)", "$1" + targetVersion + "$2");

        if (forcedOutput != null) {
            before = p;
            p = p.replaceAll("(<project\\.(?:build\\.sourceEncoding|reporting\\.outputEncoding)>)[^<]*(</project)", "$1" + forcedOutput.name() + "$2");
            p = p.replaceAll("(<encoding>)[^<$]*(</encoding>)", "$1" + forcedOutput.name() + "$2");
            if (!p.equals(before)) notes.add("Quellkodierung auf " + forcedOutput.name() + " gesetzt (--output-encoding).");
        } else {
            Matcher enc = Pattern.compile("<project\\.build\\.sourceEncoding>([^<]+)</").matcher(p);
            if (!enc.find()) notes.add("`project.build.sourceEncoding` fehlt – ohne Angabe nimmt der Compiler die Plattformkodierung; UTF-8 setzen und Dateien mit --output-encoding=UTF-8 konvertieren.");
        }
        if (p.contains("spring-boot-starter-parent") || p.contains("spring-boot-dependencies"))
            notes.add("Spring Boot: Vaadin 25 setzt Spring Boot 4 voraus – Version von spring-boot-starter-parent bzw. spring-boot-dependencies anheben.");
        if (p.contains("jetty-maven-plugin") || p.contains("jetty-ee"))
            notes.add("Jetty: Jakarta EE 11 kompatible Jetty-Version (Jetty 12, ee10/ee11-Plugin) verwenden.");
        if (p.contains("<packaging>war</packaging>"))
            notes.add("WAR-Packaging: Servlet-Container mit Servlet 6.1 / Jakarta EE 11 verwenden (z.B. Tomcat 11, Jetty 12).");

        if (!notes.isEmpty()) {
            p = p.replaceFirst("(<project\\b[^>]*>)", "$1\n    <!-- " + TODO_PREFIX.replace("[", "\\[").replace("]", "\\]")
                + "automatisch angepasst, siehe MIGRATION_REPORT.md -->");
            globalNotes.add("**" + rel + "**: " + String.join(" ", notes));
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------------
    // AppShell & Bericht
    // ------------------------------------------------------------------------------------------------

    void writeAppShell() throws IOException {
        if (!appShell.needed || appShell.dir == null) return;
        String cls = "AppShell";
        if (Files.exists(appShell.dir.resolve(cls + ".java"))) cls = "MigratedAppShell";
        StringBuilder imp = new StringBuilder();
        imp.append("import com.vaadin.flow.component.dependency.StyleSheet;\n");
        imp.append("import com.vaadin.flow.component.page.AppShellConfigurator;\n");
        if (appShell.push) imp.append("import com.vaadin.flow.component.page.Push;\n");
        if (appShell.pushArgs.contains("PushMode")) imp.append("import com.vaadin.flow.shared.communication.PushMode;\n");
        if (appShell.pushArgs.contains("Transport")) imp.append("import com.vaadin.flow.shared.ui.Transport;\n");
        imp.append("import com.vaadin.flow.theme.lumo.Lumo;\n");
        StringBuilder body = new StringBuilder();
        body.append(appShell.pkg != null ? "package " + appShell.pkg + ";\n\n" : "");
        body.append(imp).append("\n");
        body.append("/**\n * Automatisch vom VaadinMigrator erzeugt. Globale Einstellungen, die in Vaadin 7/8 an der UI-Klasse hingen,\n");
        body.append(" * gehören in Vaadin 25 an genau eine Klasse, die AppShellConfigurator implementiert.\n");
        body.append(" * Quelle: ").append(String.join(", ", appShell.sources)).append("\n */\n");
        if (appShell.push) body.append("@Push").append(appShell.pushArgs.isBlank() ? "" : appShell.pushArgs).append("\n");
        body.append("// ").append(TODO_PREFIX).append("Lumo wird in Vaadin 25 explizit geladen – alternativ Aura.STYLESHEET bzw. eigenes Stylesheet\n");
        body.append("@StyleSheet(Lumo.STYLESHEET)\n");
        if (appShell.theme != null) {
            body.append("// ").append(TODO_PREFIX).append("altes Theme ").append(appShell.theme)
                .append(" (Valo/SCSS) nicht übernommen – Styles nach src/main/resources/META-INF/resources/styles.css portieren und mit @StyleSheet(\"styles.css\") laden\n");
        }
        body.append("public class ").append(cls).append(" implements AppShellConfigurator {\n}\n");
        Path out = appShell.dir.resolve(cls + ".java");
        Path rel = singleFile ? out.getFileName() : dst.relativize(out);
        writeText(out, body.toString(), appShell.source, rel, true);
        reports.add(new FileReport(rel, true, todoLines(body.toString())));
        globalNotes.add("`" + rel + "` wurde neu erzeugt (AppShellConfigurator mit @Push/Stylesheet).");
    }

    String report() {
        StringBuilder r = new StringBuilder();
        int todos = reports.stream().mapToInt(f -> f.todos().size()).sum();
        r.append("# Migrationsbericht Vaadin 7/8 → Vaadin ").append(targetVersion).append("\n\n");
        r.append("Erstellt: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))).append("  \n");
        r.append("Quelle: `").append(src).append("`  \nZiel: `").append(dst).append("`\n\n");
        r.append("## Zusammenfassung\n\n");
        r.append("| Kennzahl | Wert |\n|---|---|\n");
        r.append("| Java-Dateien | ").append(javaFiles).append(" |\n");
        r.append("| davon mit Vaadin-Bezug | ").append(vaadinFiles).append(" |\n");
        r.append("| TODO-Markierungen | ").append(todos).append(" |\n");
        r.append("| sonstige Dateien kopiert | ").append(copied).append(" |\n\n");
        r.append("## Zeichenkodierung\n\n");
        r.append("Erkannt: ").append(charsetStats.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
            .collect(Collectors.joining(", "))).append("  \n");
        r.append("Geschrieben: ").append(forcedOutput != null ? forcedOutput.name() + " (erzwungen)" : "jeweils in der erkannten Kodierung").append("\n\n");
        for (String n : encodingNotes) r.append("- ").append(n).append("\n");
        if (!encodingNotes.isEmpty()) r.append("\n");
        if (!globalNotes.isEmpty()) {
            r.append("## Projektweite Hinweise\n\n");
            for (String n : globalNotes) r.append("- ").append(n).append("\n");
            r.append("\n");
        }
        r.append("## Dateien mit offenen Punkten\n\n");
        List<FileReport> withTodos = reports.stream().filter(f -> !f.todos().isEmpty())
            .sorted(Comparator.comparingInt((FileReport f) -> -f.todos().size())).toList();
        if (withTodos.isEmpty()) r.append("Keine.\n\n");
        for (FileReport f : withTodos) {
            r.append("### `").append(f.rel()).append("` (").append(f.todos().size()).append(")\n\n| Zeile | Hinweis |\n|---:|---|\n");
            for (String t : f.todos()) {
                String[] parts = t.split("\t", 2);
                r.append("| ").append(parts[0]).append(" | ").append(parts[1].replace("|", "\\|")).append(" |\n");
            }
            r.append("\n");
        }
        r.append("""
            ## Nächste Schritte

            1. Build-Datei prüfen (Vaadin %s, Java 21, Jakarta EE 11; mit Spring: Spring Boot 4).
            2. Kompilieren und die Fehler zusammen mit den TODO-Markierungen abarbeiten (`grep -rn "TODO \\[Vaadin-Migration\\]"`).
            3. Routen prüfen: jede frühere View bzw. UI hat ein `@Route` bekommen; Hauptlayout ggf. als `RouterLayout` bauen.
            4. Styling neu aufsetzen: Valo-SCSS wird nicht übernommen, Lumo-Varianten und eigenes CSS verwenden.
            5. Die Anwendung im Browser durchklicken – Layout-Verhalten (Margin/Padding, Ausrichtung, Größen) unterscheidet sich im Detail.
            """.formatted(targetVersion));
        return r.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // Konvertierung einer Java-Datei
    // ------------------------------------------------------------------------------------------------

    static final Pattern IMPORT = Pattern.compile("(?m)^[ \\t]*import[ \\t]+(static[ \\t]+)?([\\w.]+?)(\\.\\*)?[ \\t]*;[ \\t]*(?:/\\*@@C\\d+@@\\*/)?[ \\t]*$\\n?");
    static final Pattern DECL = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)(?:\\s*<[^;=(){}]*?>)?(?:\\s*\\[\\s*\\])?\\s+([a-z_$][A-Za-z0-9_$]*)\\s*(?=[=;,):])");
    static final Pattern LITERAL_PH = Pattern.compile("/\\*@@C(\\d+)@@\\*/|\"@@S(\\d+)@@\"");
    static final Pattern TODO_PH = Pattern.compile("/\\*@@T(\\d+)@@\\*/");
    static final Pattern ALIGN = Pattern.compile("^Alignment\\s*\\.\\s*(TOP|MIDDLE|BOTTOM)_(LEFT|CENTER|RIGHT)$");
    static final Pattern NOTIF_TYPE = Pattern.compile("^(?:Notification\\s*\\.\\s*)?Type\\s*\\.\\s*(\\w+)$");
    static final Pattern RESOURCE = Pattern.compile("(?s)^new\\s+(ThemeResource|ExternalResource)\\s*\\((.*)\\)$");
    static final String QUALIFIER = "(?:(?:[\\w$]+(?:\\s*\\(\\s*\\))?)\\s*\\.\\s*)*";
    static final Set<String> NOT_DECL_WORDS = Set.of("return", "else", "throw", "case", "yield", "assert", "do", "new");

    interface CallFn { String apply(MatchResult head, List<String> args, String full); }

    final class JavaFile {
        final Path rel, out;
        final List<String> store = new ArrayList<>();
        final List<String> todos = new ArrayList<>();
        final Set<String> used = new HashSet<>();
        final Map<String, String> varTypes = new HashMap<>();
        final Set<String> explicitImports = new LinkedHashSet<>();
        final Set<String> wildcardImports = new LinkedHashSet<>();
        final List<String> staticImports = new ArrayList<>();
        final Set<TypeMapping> active = new LinkedHashSet<>();
        String className, superType, pkg, requestParam;
        Text source;
        boolean vaadin, routeAdded, uiConverted;

        JavaFile(Path rel, Path out) { this.rel = rel; this.out = out; }

        String convert(String source) {
            boolean crlf = source.contains("\r\n");
            String c = mask(source.replace("\r\n", "\n"));
            c = jakarta(c);
            parseImports(c);
            vaadin = !(explicitImports.isEmpty() && wildcardImports.isEmpty() && staticImports.isEmpty());
            if (vaadin) {
                collectContext(c);
                c = extractOldImports(c);
                c = annotate(c, preRules());
                c = structure(c);
                c = events(c);
                c = typeAware(c);
                c = components(c);
                c = generic(c);
                c = gridChains(c);
                c = layouts(c);
                c = navigationAndPage(c);
                c = renameTypes(c);
                c = annotate(c, postRules());
                c = rebuildImports(c);
                c = restoreTodos(c);
            }
            String result = unmask(c);
            return crlf ? result.replace("\n", "\r\n") : result;
        }

        // ---------- Maskierung von Literalen und Kommentaren ----------

        String mask(String s) {
            StringBuilder out = new StringBuilder(s.length());
            int i = 0, n = s.length();
            while (i < n) {
                char ch = s.charAt(i);
                if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    int e = s.indexOf('\n', i);
                    if (e < 0) e = n;
                    out.append(comment(s.substring(i, e)));
                    i = e;
                } else if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    int e = s.indexOf("*/", i + 2);
                    e = e < 0 ? n : e + 2;
                    out.append(comment(s.substring(i, e)));
                    i = e;
                } else if (ch == '"' && s.startsWith("\"\"\"", i)) {
                    int e = i + 3;
                    while (e < n && !(s.startsWith("\"\"\"", e) && s.charAt(e - 1) != '\\')) e++;
                    e = Math.min(n, e + 3);
                    out.append(lit(s.substring(i, e)));
                    i = e;
                } else if (ch == '"' || ch == '\'') {
                    int e = i + 1;
                    while (e < n) {
                        char d = s.charAt(e);
                        if (d == '\\') { e += 2; continue; }
                        if (d == ch) { e++; break; }
                        if (d == '\n') break;
                        e++;
                    }
                    e = Math.min(e, n);
                    out.append(lit(s.substring(i, e)));
                    i = e;
                } else {
                    out.append(ch);
                    i++;
                }
            }
            return out.toString();
        }

        String comment(String text) { store.add(text); return "/*@@C" + (store.size() - 1) + "@@*/"; }

        /** Registriert ein neues Java-Literal (inkl. Anführungszeichen) und liefert den Platzhalter. */
        String lit(String javaLiteral) { store.add(javaLiteral); return "\"@@S" + (store.size() - 1) + "@@\""; }

        String unmask(String s) {
            Matcher m = LITERAL_PH.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String rep;
                if (m.group(1) != null) {
                    rep = store.get(Integer.parseInt(m.group(1)));
                    if (rep.startsWith("//") && restOfLineHasCode(s, m.end())) rep = "/* " + rep.substring(2).replace("*/", "* /").strip() + " */";
                } else {
                    rep = store.get(Integer.parseInt(m.group(2)));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        boolean restOfLineHasCode(String s, int from) {
            for (int i = from; i < s.length() && s.charAt(i) != '\n'; i++) if (!Character.isWhitespace(s.charAt(i))) return true;
            return false;
        }

        // ---------- TODO-Verwaltung ----------

        String todo(String msg) { todos.add(msg); return "/*@@T" + (todos.size() - 1) + "@@*/"; }

        String use(String simple) { used.add(simple); return simple; }

        String annotate(String code, List<LineRule> rules) {
            if (rules.isEmpty()) return code;
            String[] lines = code.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String t = line.strip();
                if (!(t.startsWith("import ") || t.startsWith("package "))) {
                    Set<String> msgs = new LinkedHashSet<>();
                    for (LineRule r : rules) if (r.pattern().matcher(line).find()) msgs.add(r.msg());
                    String indent = line.substring(0, line.length() - line.stripLeading().length());
                    for (String msg : msgs) sb.append(indent).append(todo(msg)).append('\n');
                }
                sb.append(line);
                if (i < lines.length - 1) sb.append('\n');
            }
            return sb.toString();
        }

        /** Wandelt TODO-Platzhalter in eigene Kommentarzeilen oberhalb der betroffenen Zeile um. */
        String restoreTodos(String code) {
            List<String> outLines = new ArrayList<>();
            for (String line : code.split("\n", -1)) {
                Matcher m = TODO_PH.matcher(line);
                if (!m.find()) { outLines.add(line); continue; }
                m.reset();
                Set<String> msgs = new LinkedHashSet<>();
                while (m.find()) msgs.add(todos.get(Integer.parseInt(m.group(1))));
                String rest = TODO_PH.matcher(line).replaceAll("");
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                for (String msg : msgs) {
                    String todoLine = indent + "// " + TODO_PREFIX + msg;
                    boolean dup = false;
                    for (int k = outLines.size() - 1; k >= 0 && outLines.get(k).strip().startsWith("// " + TODO_PREFIX); k--)
                        if (outLines.get(k).strip().equals(todoLine.strip())) { dup = true; break; }
                    if (!dup) outLines.add(todoLine);
                }
                if (!rest.isBlank()) outLines.add(rest.replaceAll("(\\S) {2,}(?=\\S)", "$1 "));
            }
            return String.join("\n", outLines);
        }

        // ---------- Kontext ----------

        void parseImports(String c) {
            Matcher m = IMPORT.matcher(c);
            while (m.find()) {
                String name = m.group(2);
                if (!isOldVaadin(name)) continue;
                if (m.group(1) != null) staticImports.add(name + (m.group(3) != null ? ".*" : ""));
                else if (m.group(3) != null) wildcardImports.add(name);
                else explicitImports.add(name);
            }
        }

        void collectContext(String c) {
            Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(c);
            if (pm.find()) pkg = pm.group(1);
            Matcher cm = Pattern.compile("(?<![.\\w])class\\s+(\\w+)[^{]*\\{").matcher(c);
            if (cm.find()) {
                className = cm.group(1);
                Matcher em = Pattern.compile("\\bextends\\s+([\\w.]+)").matcher(cm.group());
                if (em.find()) superType = simpleName(em.group(1));
            }
            Matcher dm = DECL.matcher(c);
            while (dm.find()) varTypes.put(dm.group(2), dm.group(1));
            String body = IMPORT.matcher(c).replaceAll("");
            for (TypeMapping tm : MAPPINGS) if (isActive(tm, body)) active.add(tm);
        }

        boolean isActive(TypeMapping tm, String body) {
            for (String o : tm.oldFqns()) {
                if (explicitImports.contains(o) || body.contains(o)) return true;
                if (wildcardImports.contains(tm.oldPackage(o)) && word(tm.oldSimple()).matcher(body).find()) return true;
            }
            return false;
        }

        boolean wasOld(String simple) {
            for (TypeMapping tm : active) if (tm.oldSimple().equals(simple)) return true;
            return false;
        }

        /** Alter Vaadin-Typ oder innere Klasse per Import/Wildcard sichtbar? */
        boolean vaadinRef(String name) {
            if (wasOld(name)) return true;
            for (String i : explicitImports) if (i.endsWith("." + name)) return true;
            for (String w : wildcardImports) if (w.endsWith("." + name)) return true;
            return false;
        }

        boolean importedNested(String nested) {
            for (String i : explicitImports) if (i.endsWith("." + nested)) return true;
            String outer = nested.substring(0, nested.indexOf('.'));
            for (String w : wildcardImports) if (w.endsWith("." + outer)) return true;
            return false;
        }

        Set<String> varsOf(Set<String> types) {
            Set<String> res = new TreeSet<>();
            for (var e : varTypes.entrySet()) if (types.contains(e.getValue()) && wasOld(e.getValue())) res.add(e.getKey());
            return res;
        }

        boolean superIs(Set<String> types) { return superType != null && types.contains(superType) && wasOld(superType); }

        String layoutKind(String var) {
            String t = (var == null || var.equals("this")) ? superType : varTypes.get(var);
            if (t == null) return "?";
            return switch (t) {
                case "HorizontalLayout" -> "H";
                case "VerticalLayout" -> "V";
                case "FormLayout", "CssLayout", "GridLayout", "AbsoluteLayout", "Panel" -> "X";
                default -> "?";
            };
        }

        List<LineRule> preRules() {
            List<LineRule> rules = new ArrayList<>(PRE_RULES);
            for (TypeMapping tm : active) if (tm.note() != null) rules.add(new LineRule(word(tm.oldSimple()), tm.note()));
            return rules;
        }

        List<LineRule> postRules() {
            List<LineRule> rules = new ArrayList<>();
            rules.add(rule("\\bValoTheme\\s*\\.", "ValoTheme-Stil: Lumo-Theme-Variante (addThemeVariants), LumoUtility- oder eigene CSS-Klasse verwenden"));
            rules.add(rule("(?<![\\w.$])Alignment\\s*\\.", "Alignment: FlexComponent.Alignment bzw. JustifyContentMode verwenden"));
            rules.add(rule("(?<![\\w.$])ContentMode\\b", "ContentMode entfällt: Span für Text, Html für HTML, Pre für vorformatierten Text"));
            Set<String> seen = new HashSet<>();
            for (TypeMapping tm : active) {
                if (tm.removed() && tm.note() == null && seen.add(tm.oldSimple())
                    && !Set.of("Alignment", "ContentMode", "ValoTheme").contains(tm.oldSimple()))
                    rules.add(new LineRule(word(tm.oldSimple()), tm.oldSimple() + " existiert in Vaadin Flow nicht mehr"));
            }
            return rules;
        }

        // ---------- Imports neu aufbauen ----------

        String rebuildImports(String c) {
            Set<String> add = new TreeSet<>();
            List<String> importTodos = new ArrayList<>();
            for (String imp : explicitImports) {
                if (BY_OLD_FQN.containsKey(imp)) continue;
                if (NESTED_SPECIAL.containsKey(imp) || NESTED_SPECIAL.containsKey(imp.replace(".v7.", "."))) {
                    String t = NESTED_SPECIAL.getOrDefault(imp, NESTED_SPECIAL.get(imp.replace(".v7.", ".")));
                    if (!t.isEmpty()) add.add(t);
                    continue;
                }
                int dot = imp.lastIndexOf('.');
                TypeMapping parent = BY_OLD_FQN.get(imp.substring(0, dot));
                if (parent != null) {
                    if (!parent.removed()) add.add(parent.newFqn() + imp.substring(dot));
                    continue;
                }
                String mapped = mapPackage(imp.substring(0, dot));
                if (mapped != null) { add.add(mapped + imp.substring(dot)); continue; }
                importTodos.add("Import " + imp + " entfernt – kein bekanntes Flow-Pendant");
            }
            for (String w : wildcardImports) {
                String mapped = mapPackage(w);
                if (mapped != null) { add.add(mapped + ".*"); continue; }
                boolean known = BY_OLD_FQN.containsKey(w) || MAPPINGS.stream().anyMatch(tm -> tm.oldFqns().stream().anyMatch(o -> tm.oldPackage(o).equals(w)));
                if (!known) importTodos.add("Wildcard-Import " + w + ".* entfernt");
            }
            for (String s : staticImports) importTodos.add("Statischer Import " + s + " entfernt");
            if (explicitImports.stream().anyMatch(i -> i.startsWith("com.vaadin.v7.")) || wildcardImports.stream().anyMatch(i -> i.startsWith("com.vaadin.v7.")))
                importTodos.add("V7-Kompatibilitätsklassen verwendet: Datenmodell (Container/Property) und java.util.Date-Felder manuell umstellen");

            String body = IMPORT.matcher(c.replace(IMPORT_MARK, "")).replaceAll("");
            Set<String> declared = new HashSet<>();
            Matcher dm = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)").matcher(body);
            while (dm.find()) declared.add(dm.group(1));
            Map<String, String> existing = new HashMap<>();
            Matcher im = IMPORT.matcher(c);
            while (im.find()) {
                if (im.group(1) == null && im.group(3) == null && !isOldVaadin(im.group(2))) {
                    String fq = im.group(2);
                    existing.put(simpleName(fq), fq);
                }
            }
            Map<String, String> candidates = new LinkedHashMap<>();
            for (TypeMapping tm : active) if (!tm.removed()) candidates.putIfAbsent(tm.newSimple(), tm.newFqn());
            for (String u : used) if (INTRODUCED.containsKey(u)) candidates.putIfAbsent(u, INTRODUCED.get(u));
            for (var e : candidates.entrySet()) {
                String simple = e.getKey(), fqn = e.getValue();
                if (declared.contains(simple) || existing.containsKey(simple)) continue;
                if (fqn.substring(0, fqn.lastIndexOf('.')).equals(pkg)) continue;
                if (word(simple).matcher(body).find()) add.add(fqn);
            }
            add.removeIf(existing::containsValue);

            StringBuilder block = new StringBuilder();
            for (String t : importTodos) block.append(todo(t)).append('\n');
            for (String a : add) block.append("import ").append(a).append(";\n");

            int mark = c.indexOf(IMPORT_MARK + "\n");
            if (mark >= 0) return c.substring(0, mark) + block + c.substring(mark + IMPORT_MARK.length() + 1);
            if (block.isEmpty()) return c;
            Matcher pm = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;[ \\t]*\\n").matcher(c);
            int insertAt = pm.find() ? pm.end() : 0;
            return c.substring(0, insertAt) + "\n" + block + c.substring(insertAt);
        }

        static final String IMPORT_MARK = "/*@@IMPORTS@@*/";

        /** Entfernt alte Vaadin-Imports und setzt eine Marke, damit keine Transformation sie verändert. */
        String extractOldImports(String c) {
            Matcher m = IMPORT.matcher(c);
            StringBuilder sb = new StringBuilder();
            boolean marked = false;
            while (m.find()) {
                if (isOldVaadin(m.group(2))) {
                    m.appendReplacement(sb, marked ? "" : Matcher.quoteReplacement(IMPORT_MARK + "\n"));
                    marked = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            return sb.toString();
        }

        String mapPackage(String pkgName) {
            for (var e : PACKAGE_MAP.entrySet()) {
                String old = e.getKey();
                if (pkgName.equals(old) || pkgName.equals(old.replace("com.vaadin.", "com.vaadin.v7."))) return e.getValue();
            }
            return null;
        }

        // ---------- Transformationen ----------

        String jakarta(String c) {
            return c.replaceAll("\\bjavax\\.(servlet|validation|persistence|inject|ws\\.rs|ejb|enterprise|faces|mail|websocket|el|interceptor|json"
                + "|transaction(?!\\.xa)|annotation\\.(?:PostConstruct|PreDestroy|Resource|Priority|ManagedBean|security))\\b", "jakarta.$1");
        }

        /** Klassenstruktur: UI, View, Servlet, Annotationen, Composite, Window, attach/detach. */
        String structure(String c) {
            c = rx(c, "(?s)\\n?[ \\t]*@WebServlet\\s*\\([^)]*\\)\\s*(?:@VaadinServletConfiguration\\s*\\([^)]*\\)\\s*)?(?:public\\s+)?static\\s+class\\s+\\w+\\s+extends\\s+VaadinServlet\\s*\\{\\s*\\}",
                mr -> "");
            if (wasOld("VaadinServletConfiguration"))
                c = rx(c, "@VaadinServletConfiguration\\s*\\([^)]*\\)", mr -> todo("@VaadinServletConfiguration entfernt – Einstellungen über application.properties bzw. Servlet-Init-Parameter"));
            if (wasOld("VaadinServlet"))
                c = rx(c, "\\bextends\\s+VaadinServlet\\b", mr -> todo("Eigenes VaadinServlet ist meist unnötig – Initialisierung per VaadinServiceInitListener") + mr.group());
            if (wasOld("Widgetset")) c = rx(c, "@Widgetset\\s*(?:\\([^)]*\\))?\\s*", mr -> "");
            if (wasOld("Push")) c = rx(c, "@Push\\b(\\s*\\([^)]*\\))?\\s*", mr -> {
                appShell.push = true;
                if (mr.group(1) != null) appShell.pushArgs = unmask(mr.group(1)).strip();
                noteAppShell();
                return "";
            });
            if (wasOld("Theme")) c = rx(c, "@Theme\\s*\\(([^)]*)\\)\\s*", mr -> {
                appShell.theme = unmask(mr.group(1)).strip();
                noteAppShell();
                return "";
            });
            if (wasOld("SpringUI")) c = rx(c, "@SpringUI\\b(\\s*\\(([^)]*)\\))?", mr -> {
                String path = mr.group(2) == null ? null : annotationValue(mr.group(2), "path");
                routeAdded = true;
                return "@" + use("Route") + "(" + (path == null || path.isBlank() ? lit("\"\"") : routeLit(path)) + ")";
            });
            if (wasOld("SpringView")) c = rx(c, "@SpringView\\b(\\s*\\(([^)]*)\\))?", mr -> {
                String name = mr.group(2) == null ? null : annotationValue(mr.group(2), "name");
                routeAdded = true;
                return "@" + use("Route") + "(" + (name == null || name.isBlank() ? lit("\"" + defaultRoute() + "\"") : routeLit(name)) + ")";
            });
            if (wasOld("SpringViewDisplay"))
                c = rx(c, "@SpringViewDisplay\\b\\s*", mr -> todo("@SpringViewDisplay entfällt – Hauptlayout implementiert RouterLayout, Views mit @Route(value = ..., layout = Hauptlayout.class)"));

            // UI-Klasse -> Route-View
            if ("UI".equals(superType) && wasOld("UI")) {
                uiConverted = true;
                c = c.replaceFirst("\\bextends\\s+UI\\b", "extends " + use("VerticalLayout"));
                c = rx(c, "(?:@Override\\s+)?(?:protected|public)\\s+void\\s+init\\s*\\(\\s*(?:final\\s+)?VaadinRequest\\s+(\\w+)\\s*\\)\\s*\\{", mr -> {
                    requestParam = mr.group(1);
                    return "public " + className + "() {";
                });
                if (requestParam != null && word(requestParam).matcher(c).find())
                    c = rx(c, "(?<![\\w$.])" + Pattern.quote(requestParam) + "(?![\\w$])", mr -> use("VaadinRequest") + ".getCurrent()");
                if (!routeAdded) { c = beforeClass(c, "@" + use("Route") + "(" + lit("\"\"") + ")"); routeAdded = true; }
                c = beforeClass(c, todo("Ehemalige UI-Klasse: jetzt Route-View (VerticalLayout); @Push/@Theme stehen in der AppShell-Klasse"));
                c = bare(c, "setContent", "add");
                c = bare(c, "getPage", use("UI") + ".getCurrent().getPage");
                c = bare(c, "getSession", use("VaadinSession") + ".getCurrent");
                c = bare(c, "access", "UI.getCurrent().access");
                c = bare(c, "setPollInterval", "UI.getCurrent().setPollInterval");
                c = bare(c, "setLocale", "UI.getCurrent().setLocale");
                c = bare(c, "setErrorHandler", use("VaadinSession") + ".getCurrent().setErrorHandler");
                use("UI");
                noteAppShell();
            }

            // Navigator-View -> Route
            if (wasOld("View") && className != null && Pattern.compile("\\bimplements\\b[^{]*\\bView\\b").matcher(c).find()) {
                Pattern enter = Pattern.compile("(?:public\\s+)?void\\s+enter\\s*\\(\\s*(?:final\\s+)?(?:ViewChangeListener\\s*\\.\\s*)?ViewChangeEvent\\s+(\\w+)\\s*\\)");
                boolean hasEnter = enter.matcher(c).find();
                c = rewriteImplements(c, list -> {
                    list.removeIf(t -> t.equals("View"));
                    if (hasEnter) list.add(use("BeforeEnterObserver"));
                });
                c = rx(c, enter.pattern(), mr -> "public void beforeEnter(" + use("BeforeEnterEvent") + " " + mr.group(1) + ")");
                if (!routeAdded) {
                    Matcher vn = Pattern.compile("\\bstatic\\s+final\\s+String\\s+(VIEW_NAME|NAME|ROUTE)\\s*=").matcher(c);
                    String route = vn.find() ? vn.group(1) : lit("\"" + defaultRoute() + "\"");
                    c = beforeClass(c, "@" + use("Route") + "(" + route + ")");
                    routeAdded = true;
                }
                if (superType == null) c = beforeClass(c, todo("View ohne Komponenten-Basisklasse – von einer Komponente (z.B. VerticalLayout) erben lassen"));
            }

            // CustomComponent / Composite
            if (superIs(Set.of("CustomComponent", "Composite"))) {
                c = c.replaceFirst("\\bextends\\s+(?:CustomComponent|Composite)\\b(?!\\s*<)", "extends " + use("Composite") + "<" + use("Div") + ">");
                c = bare(c, "setCompositionRoot", "getContent().add");
                c = bare(c, "getCompositionRoot", "getContent");
                c = beforeClass(c, todo("Composite<Div>: Root-Typ ggf. anpassen (z.B. Composite<VerticalLayout>) bzw. initContent() überschreiben"));
            }
            Set<String> win = Set.of("Window");
            c = bareIn(c, win, "setCaption", "setHeaderTitle", null);
            c = bareIn(c, win, "getCaption", "getHeaderTitle", null);
            c = bareIn(c, win, "setContent", "add", null);
            List<Region> rs = regions(c);
            c = rx(c, "(?m)^[ \\t]*(?:this\\s*\\.\\s*)?center\\s*\\(\\s*\\)\\s*;[ \\t]*\\n", mr -> inType(rs, mr.start(), win) ? "" : mr.group());
            c = bareIn(c, Set.of("Panel"), "setContent", "add", null);

            // attach()/detach() -> onAttach/onDetach
            if (superType != null && wasOld(superType)) {
                c = rx(c, "(?:public|protected)\\s+void\\s+attach\\s*\\(\\s*\\)", mr -> "protected void onAttach(" + use("AttachEvent") + " attachEvent)");
                c = c.replaceAll("\\bsuper\\s*\\.\\s*attach\\s*\\(\\s*\\)", "super.onAttach(attachEvent)");
                c = rx(c, "(?:public|protected)\\s+void\\s+detach\\s*\\(\\s*\\)", mr -> "protected void onDetach(" + use("DetachEvent") + " detachEvent)");
                c = c.replaceAll("\\bsuper\\s*\\.\\s*detach\\s*\\(\\s*\\)", "super.onDetach(detachEvent)");
            }
            return c;
        }

        void noteAppShell() {
            appShell.needed = true;
            if (appShell.dir == null) {
                appShell.dir = out.getParent();
                if (source != null) appShell.source = new Text("", source.charset(), source.bom());
                appShell.pkg = pkg;
            }
            if (!appShell.sources.contains(rel.toString())) appShell.sources.add(rel.toString());
        }

        String defaultRoute() {
            String n = className == null ? "view" : className.replaceFirst("View$", "");
            if (n.isEmpty()) return "";
            return n.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        }

        /** Routen-Ausdruck; führenden Slash aus String-Literalen entfernen. */
        String routeLit(String expr) {
            String e = expr.strip();
            if (e.matches("\"@@S\\d+@@\"")) {
                String raw = unmask(e);
                if (raw.startsWith("\"/")) return lit("\"" + raw.substring(2));
                return e;
            }
            return e;
        }

        String annotationValue(String args, String key) {
            Matcher m = Pattern.compile("\\b" + key + "\\s*=\\s*([^,]+)").matcher(args);
            if (m.find()) return m.group(1).strip();
            return args.contains("=") ? null : args.strip();
        }

        String beforeClass(String c, String text) {
            if (className == null) return c;
            Matcher m = Pattern.compile("(?m)^([ \\t]*)((?:(?:public|protected|private|final|abstract|static)\\s+)*class\\s+" + Pattern.quote(className) + "\\b)").matcher(c);
            if (!m.find()) return c;
            return c.substring(0, m.start()) + m.group(1) + text + "\n" + c.substring(m.start());
        }

        String rewriteImplements(String c, java.util.function.Consumer<List<String>> edit) {
            Matcher m = Pattern.compile("(\\bclass\\s+" + Pattern.quote(className) + "\\b[^{]*?)\\s+implements\\s+([^{]+?)(\\s*\\{)").matcher(c);
            if (!m.find()) return c;
            List<String> list = new ArrayList<>(splitTypes(m.group(2)));
            edit.accept(list);
            String repl = m.group(1) + (list.isEmpty() ? "" : " implements " + String.join(", ", list)) + m.group(3);
            return c.substring(0, m.start()) + repl + c.substring(m.end());
        }

        /** Events, Listener, Tastenkürzel. */
        String events(String c) {
            boolean nestedClick = importedNested("Button.ClickEvent");
            boolean nestedListener = importedNested("Button.ClickListener");
            if (wasOld("Button") || nestedClick || nestedListener) {
                c = rx(c, "\\bButton\\s*\\.\\s*ClickListener\\b", mr -> use("ComponentEventListener") + "<" + use("ClickEvent") + "<" + use("Button") + ">>");
                if (nestedListener) c = rx(c, "(?<![\\w$.])ClickListener\\b", mr -> use("ComponentEventListener") + "<" + use("ClickEvent") + "<" + use("Button") + ">>");
                c = rx(c, "\\bButton\\s*\\.\\s*ClickEvent\\b", mr -> use("ClickEvent") + "<" + use("Button") + ">");
                if (nestedClick) c = rx(c, "(?<![\\w$.])ClickEvent\\b(?!\\s*<)", mr -> use("ClickEvent") + "<" + use("Button") + ">");
                c = c.replaceAll("\\bbuttonClick\\s*\\(", "onComponentEvent(");
                c = c.replaceAll("\\.\\s*getButton\\s*\\(\\s*\\)", ".getSource()");
            }
            if (vaadinRef("ViewChangeListener") || vaadinRef("ViewChangeEvent"))
                c = rx(c, "\\b(?:ViewChangeListener\\s*\\.\\s*)?ViewChangeEvent\\b", mr -> use("BeforeEnterEvent"));
            c = c.replaceAll("\\.\\s*isUserOriginated\\s*\\(\\s*\\)", ".isFromClient()");
            if (vaadinRef("ShortcutAction") || vaadinRef("KeyCode")) {
                c = rx(c, "\\b(?:com\\.vaadin\\.event\\.)?(?:ShortcutAction\\s*\\.\\s*)?KeyCode\\s*\\.\\s*(\\w+)", mr -> use("Key") + "." + mapKey(mr.group(1)));
                c = rx(c, "\\b(?:com\\.vaadin\\.event\\.)?(?:ShortcutAction\\s*\\.\\s*)?ModifierKey\\s*\\.\\s*(\\w+)", mr -> use("KeyModifier") + "." + (mr.group(1).equals("CTRL") ? "CONTROL" : mr.group(1)));
            }
            c = ren(c, "setClickShortcut", "addClickShortcut");
            if (wasOld("ValueChangeMode")) c = c.replaceAll("\\bValueChangeMode\\s*\\.\\s*BLUR\\b", "ValueChangeMode.ON_BLUR");
            if (wasOld("Sizeable") || vaadinRef("Unit")) c = rx(c, "\\bSizeable\\s*\\.\\s*Unit\\b", mr -> use("Unit"));
            if (wasOld("MenuBar")) c = rx(c, "\\bMenuBar\\s*\\.\\s*MenuItem\\b", mr -> use("MenuItem"));
            return c;
        }

        String mapKey(String k) {
            if (k.matches("[A-Z]")) return "KEY_" + k;
            if (k.matches("NUM[0-9]")) return "DIGIT_" + k.charAt(3);
            if (k.matches("F\\d{1,2}")) return k;
            return switch (k) {
                case "ESCAPE", "ENTER", "TAB", "SPACE", "BACKSPACE", "DELETE", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "INSERT",
                     "ARROW_UP", "ARROW_DOWN", "ARROW_LEFT", "ARROW_RIGHT" -> k;
                default -> k + todo("Tastencode " + k + " auf passende Key-Konstante prüfen");
            };
        }

        /** Methoden, deren Bedeutung vom (alten) Typ der Variablen abhängt. */
        String typeAware(String c) {
            for (TypedRule r : TYPED_RULES) c = applyTyped(c, r.types(), r.method(), r.replacement(), r.todo());

            Set<String> windows = varsOf(Set.of("Window"));
            if (!windows.isEmpty()) {
                c = c.replaceAll("(?m)^[ \\t]*\\b(?:" + alt(windows) + ")\\s*\\.\\s*center\\s*\\(\\s*\\)\\s*;[ \\t]*\\n", "");
                c = c.replaceAll("\\b(?:" + alt(windows) + ")\\s*\\.\\s*center\\s*\\(\\s*\\)\\s*;", "");
            }
            Set<String> tabTypes = Set.of("TabSheet", "Accordion");
            Set<String> tabs = varsOf(tabTypes);
            if (!tabs.isEmpty() || superIs(tabTypes)) {
                String vars = tabs.isEmpty() ? "this" : alt(tabs) + "|this";
                c = calls(c, "(?:\\b(" + vars + ")\\s*\\.\\s*)?(?<![\\w$])addTab\\s*\\(", (h, a, f) -> {
                    boolean qualified = h.group(1) != null;
                    if (!qualified && !superIs(tabTypes)) return null;
                    String q = qualified ? h.group(1) + "." : "";
                    if (a.size() >= 2) return (a.size() > 2 ? todo("Tab-Icon/-Position nicht übernommen") : "") + q + "add(" + a.get(1) + ", " + a.get(0) + ")";
                    return todo("addTab ohne Titel: add(\"Titel\", inhalt) verwenden") + f;
                });
            }
            Set<String> grids = varsOf(GRIDS);
            if (!grids.isEmpty())
                c = calls(c, "\\b(" + alt(grids) + ")\\s*\\.\\s*removeColumn\\s*\\(", (h, a, f) ->
                    a.size() == 1 && isStringLit(a.get(0)) ? h.group(1) + ".removeColumnByKey(" + a.get(0) + ")" : null);
            c = c.replaceAll("\\bsetDuration\\s*\\(\\s*-1\\s*\\)", "setDuration(0)");
            return c;
        }

        String applyTyped(String c, Set<String> types, String method, String repl, String todoMsg) {
            Set<String> vars = varsOf(types);
            String target = repl != null ? repl : method;
            if (!vars.isEmpty()) {
                c = rx(c, "\\b(" + alt(vars) + ")(\\s*\\.\\s*)" + method + "(?=\\s*\\()",
                    mr -> (todoMsg != null ? todo(todoMsg) : "") + mr.group(1) + mr.group(2) + target);
            }
            return bareIn(c, types, method, target, todoMsg);
        }

        record Region(int start, int end, String sup) {}

        /** Alle (auch verschachtelte) Klassenrümpfe mit ihrer Basisklasse. */
        List<Region> regions(String code) {
            List<Region> rs = new ArrayList<>();
            Matcher m = Pattern.compile("(?<![.\\w])class\\s+(\\w+)([^{;]*)\\{").matcher(code);
            while (m.find()) {
                int end = closingBrace(code, m.end() - 1);
                Matcher em = Pattern.compile("\\bextends\\s+([\\w.]+)").matcher(m.group(2));
                rs.add(new Region(m.start(), end < 0 ? code.length() : end, em.find() ? simpleName(em.group(1)) : null));
            }
            return rs;
        }

        String superAt(List<Region> rs, int idx) {
            Region best = null;
            for (Region r : rs) if (r.start() <= idx && idx <= r.end() && (best == null || r.start() > best.start())) best = r;
            return best == null ? null : best.sup();
        }

        boolean inType(List<Region> rs, int idx, Set<String> types) {
            String sup = superAt(rs, idx);
            return sup != null && types.contains(sup) && wasOld(sup);
        }

        /** Unqualifizierte Aufrufe innerhalb von Klassen, deren (alte) Basisklasse in types liegt. */
        String bareIn(String c, Set<String> types, String method, String repl, String todoMsg) {
            List<Region> rs = regions(c);
            if (rs.stream().noneMatch(r -> r.sup() != null && types.contains(r.sup()) && wasOld(r.sup()))) return c;
            final String code = c;
            return rx(c, "(?<![\\w$.])((?:this\\s*\\.\\s*)?)" + Pattern.quote(method) + "(?=\\s*\\()", mr -> {
                if (!inType(rs, mr.start(), types) || isDeclaration(code, mr.start())) return mr.group();
                return (todoMsg != null ? todo(todoMsg) : "") + mr.group(1) + repl;
            });
        }

        /** Konstruktoren und Komponenten-spezifische Aufrufe. */
        String components(String c) {
            if (wasOld("Label")) c = calls(c, "\\bnew\\s+Label\\s*\\(", (h, a, f) -> {
                if (a.size() != 2 || !a.get(1).contains("ContentMode")) return null;
                if (a.get(1).matches("(?:ContentMode\\s*\\.\\s*)?TEXT")) return "new Label(" + a.get(0) + ")";
                return todo("HTML-Label: new Html(\"<span>\" + text + \"</span>\") verwenden (XSS beachten) bzw. Pre für PREFORMATTED") + "new Label(" + a.get(0) + ")";
            });
            for (String t : List.of("TextField", "TextArea", "PasswordField")) {
                if (!wasOld(t)) continue;
                c = calls(c, "\\bnew\\s+" + t + "\\s*\\(", (h, a, f) -> {
                    if (a.size() != 2) return null;
                    if (isStringLit(a.get(1))) return "new " + t + "(" + a.get(0) + ", " + a.get(1) + ", " + lit("\"\"") + ")";
                    if (a.get(1).contains("->") || a.get(1).contains("::") || a.get(1).startsWith("new ")) return null;
                    return todo(t + "(String, String): in Flow ist das 2. Argument der Placeholder – für einen Startwert (label, wert, \"\") verwenden") + f;
                });
            }
            if (wasOld("VerticalSplitPanel")) c = calls(c, "\\bnew\\s+VerticalSplitPanel\\s*\\(", (h, a, f) ->
                "new VerticalSplitPanel(" + (a.isEmpty() ? "" : String.join(", ", a) + ", ") + use("SplitLayout") + ".Orientation.VERTICAL)");
            if (wasOld("Panel")) c = calls(c, "\\bnew\\s+Panel\\s*\\(", (h, a, f) -> {
                if (a.isEmpty()) return null;
                if (isStringLit(a.get(0))) {
                    List<String> rest = a.subList(1, a.size());
                    return "new Panel(new " + use("H3") + "(" + a.get(0) + ")" + (rest.isEmpty() ? "" : ", " + String.join(", ", rest)) + ")";
                }
                return a.size() == 1 ? todo("Panel(...): Argument ist Caption oder Inhalt? Caption als H3 einfügen") + f : null;
            });
            if (wasOld("ProgressBar")) c = calls(c, "\\bnew\\s+ProgressBar\\s*\\(", (h, a, f) -> a.size() == 1 ? "new ProgressBar(0, 1, " + a.get(0) + ")" : null);
            if (wasOld("Link")) c = calls(c, "\\bnew\\s+Link\\s*\\(", (h, a, f) -> {
                if (a.size() != 2) return null;
                Matcher r = RESOURCE.matcher(a.get(1));
                if (r.matches() && r.group(1).equals("ExternalResource")) return "new " + use("Anchor") + "(" + r.group(2).strip() + ", " + a.get(0) + ")";
                return null;
            });
            if (wasOld("Image")) c = calls(c, "\\bnew\\s+Image\\s*\\(", (h, a, f) -> {
                if (a.size() != 2) return null;
                Matcher r = RESOURCE.matcher(a.get(1));
                if (!r.matches()) return null;
                String alt = a.get(0).equals("null") ? lit("\"\"") : a.get(0);
                String pre = r.group(1).equals("ThemeResource") ? todo("ThemeResource-Pfad: Datei nach src/main/resources/META-INF/resources/ verschieben und Pfad anpassen") : "";
                return pre + "new " + use("Image") + "(" + r.group(2).strip() + ", " + alt + ")";
            });
            if (wasOld("BrowserFrame")) c = calls(c, "\\bnew\\s+BrowserFrame\\s*\\(", (h, a, f) -> {
                if (a.size() != 2) return null;
                Matcher r = RESOURCE.matcher(a.get(1));
                if (!r.matches() || !r.group(1).equals("ExternalResource")) return null;
                return (a.get(0).equals("null") ? "" : todo("BrowserFrame-Caption nicht übernommen")) + "new " + use("IFrame") + "(" + r.group(2).strip() + ")";
            });
            if (wasOld("Grid") || wasOld("TreeGrid")) c = calls(c, "\\baddColumn\\s*\\(", (h, a, f) ->
                a.size() == 2 && a.get(1).matches("new\\s+ComponentRenderer\\s*(?:<\\s*>)?\\s*\\(\\s*\\)") ? "addComponentColumn(" + a.get(0) + ")" : null);

            if (wasOld("Notification")) {
                c = c.replaceAll("\\.\\s*show\\s*\\(\\s*(?:Page\\s*\\.\\s*getCurrent\\s*\\(\\s*\\)|(?:UI\\s*\\.\\s*getCurrent\\s*\\(\\s*\\)\\s*\\.\\s*|getUI\\s*\\(\\s*\\)\\s*\\.\\s*)?getPage\\s*\\(\\s*\\))\\s*\\)", ".open()");
                c = calls(c, "\\bNotification\\s*\\.\\s*show\\s*\\(", (h, a, f) -> {
                    if (a.size() < 2) return null;
                    Matcher tm = NOTIF_TYPE.matcher(a.get(a.size() - 1));
                    if (!tm.matches())
                        return todo("Notification.show: Typ nicht erkannt – Varianten per addThemeVariants(NotificationVariant...) setzen") + f;
                    String text = a.size() >= 3 ? a.get(0) + " + " + lit("\" - \"") + " + " + a.get(1) : a.get(0);
                    String base = "Notification.show(" + text + ")";
                    return switch (tm.group(1)) {
                        case "ERROR_MESSAGE" -> base + ".addThemeVariants(" + use("NotificationVariant") + ".LUMO_ERROR)";
                        case "WARNING_MESSAGE" -> base + ".addThemeVariants(" + use("NotificationVariant") + ".LUMO_WARNING)";
                        case "TRAY_NOTIFICATION" -> base + ".setPosition(Notification.Position.BOTTOM_END)";
                        default -> base;
                    };
                });
                c = calls(c, "\\bnew\\s+Notification\\s*\\(", (h, a, f) -> {
                    if (a.size() < 2) return null;
                    int typeIdx = -1;
                    for (int i = 0; i < a.size(); i++) if (NOTIF_TYPE.matcher(a.get(i)).matches()) typeIdx = i;
                    if (typeIdx < 0) return a.size() == 2 ? "new Notification(" + a.get(0) + " + " + lit("\" - \"") + " + " + a.get(1) + ")" : null;
                    String text = typeIdx >= 2 ? a.get(0) + " + " + lit("\" - \"") + " + " + a.get(1) : a.get(0);
                    Matcher tm = NOTIF_TYPE.matcher(a.get(typeIdx));
                    tm.matches();
                    String variant = switch (tm.group(1)) {
                        case "ERROR_MESSAGE" -> "LUMO_ERROR";
                        case "WARNING_MESSAGE" -> "LUMO_WARNING";
                        default -> null;
                    };
                    return (variant != null ? todo("Variante setzen: notification.addThemeVariants(NotificationVariant." + variant + ")") : "")
                        + (a.size() > typeIdx + 1 ? todo("HTML-Inhalt: Notification mit Html-Komponente befüllen") : "")
                        + "new Notification(" + text + ")";
                });
            }
            if (wasOld("Position"))
                c = rx(c, "(?<![\\w$.])Position\\s*\\.\\s*(TOP|MIDDLE|BOTTOM)_(LEFT|CENTER|RIGHT)\\b", mr -> use("Notification") + ".Position." + switch (mr.group(1)) {
                    case "MIDDLE" -> "MIDDLE";
                    default -> mr.group(1) + "_" + switch (mr.group(2)) { case "LEFT" -> "START"; case "RIGHT" -> "END"; default -> "CENTER"; };
                });
            if (wasOld("VaadinIcons"))
                c = rx(c, "\\bVaadinIcons\\s*\\.\\s*([A-Z0-9_]+)\\b", mr -> use("VaadinIcon") + "." + mr.group(1) + ".create()");
            if (wasOld("FontAwesome"))
                c = rx(c, "\\bFontAwesome\\s*\\.\\s*([A-Z0-9_]+)\\b", mr -> todo("FontAwesome." + mr.group(1) + ": passendes VaadinIcon wählen") + use("VaadinIcon") + "." + mr.group(1) + ".create()");
            return c;
        }

        /** Methodennamen, die eindeutig Vaadin-spezifisch sind. */
        String generic(String c) {
            c = calls(c, "(?<![\\w$])addComponent\\s*\\(", (h, a, f) -> switch (a.size()) {
                case 1 -> "add(" + a.get(0) + ")";
                case 2 -> "addComponentAtIndex(" + a.get(1) + ", " + a.get(0) + ")";
                default -> todo("GridLayout.addComponent(c, spalte, zeile ...): Positionierung per CSS Grid nachbauen") + "add(" + a.get(0) + ")";
            });
            if (wasOld("ValoTheme")) c = calls(c, "(?<![\\w$])(addStyleNames?|setStyleName)\\s*\\(", (h, a, f) -> {
                List<String> variants = new ArrayList<>();
                String enumType = null;
                int mapped = 0;
                for (String arg : a) {
                    Matcher vm = Pattern.compile("^ValoTheme\\s*\\.\\s*(\\w+)$").matcher(arg);
                    String v = vm.matches() ? VALO.get(vm.group(1)) : null;
                    if (v == null) continue;
                    String et = v.substring(0, v.indexOf('.'));
                    if (enumType != null && !enumType.equals(et)) continue;
                    enumType = et;
                    variants.add(use(et) + v.substring(et.length()));
                    mapped++;
                }
                if (mapped == 0) return null;
                if (mapped == a.size()) return "addThemeVariants(" + String.join(", ", variants) + ")";
                return todo("Gemischte Stilnamen – Lumo-Varianten: addThemeVariants(" + String.join(", ", variants) + ")") + f;
            });
            String[][] renames = {
                {"addComponents", "add"}, {"removeComponent", "remove"}, {"removeAllComponents", "removeAll"},
                {"replaceComponent", "replace"}, {"addStyleNames", "addClassNames"}, {"addStyleName", "addClassName"},
                {"setStyleName", "setClassName"}, {"removeStyleName", "removeClassName"}, {"getStyleName", "getClassName"},
                {"setPrimaryStyleName", "setClassName"}, {"setItemCaptionGenerator", "setItemLabelGenerator"},
                {"setInputPrompt", "setPlaceholder"}};
            for (String[] r : renames) c = ren(c, r[0], r[1]);
            c = c.replaceAll("\\bsetWidthUndefined\\s*\\(\\s*\\)", "setWidth(null)");
            c = c.replaceAll("\\bsetHeightUndefined\\s*\\(\\s*\\)", "setHeight(null)");
            c = removeCalls(c, "setImmediate|setResponsive|setCaptionAsHtml|setWidgetset");
            return c;
        }

        /** Grid-Spalten: .addColumn(...).setCaption(...).setId(...) ... */
        String gridChains(String c) {
            if (!(wasOld("Grid") || wasOld("TreeGrid") || wasOld("Table"))) return c;
            Matcher m = Pattern.compile("\\.\\s*(addColumn|addComponentColumn|getColumn|getColumnByKey)\\s*\\(").matcher(c);
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            while (m.find(pos)) {
                int open = m.end() - 1, close = closingParen(c, open);
                if (close < 0) break;
                sb.append(c, pos, m.start());
                String start = m.group(1), argText = c.substring(open + 1, close);
                if (start.equals("getColumn") && isStringLit(argText.strip())) start = "getColumnByKey";
                sb.append('.').append(start).append('(').append(argText).append(')');
                int i = close + 1;
                while (true) {
                    int j = skipWs(c, i);
                    if (j >= c.length() || c.charAt(j) != '.') break;
                    int k = skipWs(c, j + 1), nameEnd = k;
                    while (nameEnd < c.length() && Character.isJavaIdentifierPart(c.charAt(nameEnd))) nameEnd++;
                    String name = c.substring(k, nameEnd);
                    int p = skipWs(c, nameEnd);
                    if (name.isEmpty() || p >= c.length() || c.charAt(p) != '(') break;
                    int q = closingParen(c, p);
                    if (q < 0) break;
                    sb.append(c, i, j).append(chainCall(name, c.substring(p + 1, q)));
                    i = q + 1;
                }
                pos = i;
            }
            sb.append(c.substring(pos));
            return sb.toString();
        }

        String chainCall(String name, String args) {
            String a = args.strip();
            return switch (name) {
                case "setCaption" -> ".setHeader(" + args + ")";
                case "setId" -> ".setKey(" + args + ")";
                case "setExpandRatio" -> ".setFlexGrow(" + args + ")";
                case "setWidth" -> a.matches("\\d+(\\.\\d+)?[dDfF]?")
                    ? ".setWidth(" + lit("\"" + a.replaceAll("[dDfF]$", "").replaceAll("\\.0+$", "") + "px\"") + ")"
                    : ".setWidth(" + args + ")";
                case "setHidden" -> ".setVisible(!(" + a + "))";
                case "setStyleGenerator" -> ".setClassNameGenerator(" + args + ")";
                case "setDescriptionGenerator" -> ".setTooltipGenerator(" + args + ")";
                case "setMinimumWidth", "setMaximumWidth", "setHidable", "setRenderer", "setEditorComponent", "setEditorBinding",
                     "setMinimumWidthFromContent", "setCaptionAsHtml", "setHidingToggleCaption" ->
                    todo("Grid.Column#" + name + " hat kein direktes Flow-Pendant") + "." + name + "(" + args + ")";
                default -> "." + name + "(" + args + ")";
            };
        }

        /** Ausrichtung, Expand-Ratio, Fenster. */
        String layouts(String c) {
            if (wasOld("Alignment")) {
                c = calls(c, "(?:\\b([\\w$]+)\\s*\\.\\s*)?(?<![\\w$])setComponentAlignment\\s*\\(", (h, a, f) -> {
                    if (a.size() != 2) return null;
                    Matcher am = ALIGN.matcher(a.get(1));
                    if (!am.matches()) return null;
                    String var = h.group(1), kind = layoutKind(var);
                    if (kind.equals("X")) return todo("setComponentAlignment in diesem Layout-Typ: per CSS (justify-self/align-self) lösen") + f;
                    String q = var != null ? var + "." : "";
                    boolean horizontal = kind.equals("H");
                    String cross = horizontal ? vert(am.group(1)) : horiz(am.group(2));
                    String main = horizontal ? horiz(am.group(2)) : vert(am.group(1));
                    String res = q + "setAlignSelf(" + use("FlexComponent") + ".Alignment." + cross + ", " + a.get(0) + ")";
                    if (kind.equals("?")) res = todo("Layout-Typ unbekannt – als VerticalLayout umgesetzt, bitte prüfen") + res;
                    if (!main.equals("START"))
                        res = todo("Ausrichtung in der Hauptachse (" + main + ") ist Sache des Layouts: setJustifyContentMode(JustifyContentMode." + main + ") oder Platzhalter/expand verwenden") + res;
                    return res;
                });
                c = calls(c, "(?:\\b([\\w$]+)\\s*\\.\\s*)?(?<![\\w$])setDefaultComponentAlignment\\s*\\(", (h, a, f) -> {
                    if (a.size() != 1) return null;
                    Matcher am = ALIGN.matcher(a.get(0));
                    if (!am.matches()) return null;
                    String var = h.group(1), kind = layoutKind(var);
                    if (kind.equals("X")) return todo("Standard-Ausrichtung per CSS lösen") + f;
                    String q = var != null ? var + "." : "";
                    boolean horizontal = kind.equals("H");
                    String fc = use("FlexComponent");
                    String res = horizontal
                        ? q + "setDefaultVerticalComponentAlignment(" + fc + ".Alignment." + vert(am.group(1)) + ")"
                        : q + "setDefaultHorizontalComponentAlignment(" + fc + ".Alignment." + horiz(am.group(2)) + ")";
                    String main = horizontal ? horiz(am.group(2)) : vert(am.group(1));
                    boolean safeQualifier = var != null || !precededByDot(cur, h.start());
                    if (!main.equals("START") && safeQualifier) res += "; " + q + "setJustifyContentMode(" + fc + ".JustifyContentMode." + main + ")";
                    if (kind.equals("?")) res = todo("Layout-Typ unbekannt – als VerticalLayout umgesetzt, bitte prüfen") + res;
                    return res;
                });
            }
            c = calls(c, "(?:\\b([\\w$]+)\\s*\\.\\s*)?(?<![\\w$])setExpandRatio\\s*\\(", (h, a, f) -> {
                if (a.size() != 2) return null;
                String var = h.group(1);
                if (layoutKind(var).equals("X")) return todo("setExpandRatio: nur in Vertical-/HorizontalLayout – sonst per CSS flex-grow") + f;
                return (var != null ? var + "." : "") + "setFlexGrow(" + a.get(1) + ", " + a.get(0) + ")";
            });
            c = calls(c, QUALIFIER + "addWindow\\s*\\(", (h, a, f) -> a.size() == 1 ? a.get(0) + ".open()" : null);
            c = calls(c, QUALIFIER + "removeWindow\\s*\\(", (h, a, f) -> a.size() == 1 ? a.get(0) + ".close()" : null);
            return c;
        }

        /** Navigation, Page, getUI(), JavaScript. */
        String navigationAndPage(String c) {
            c = rx(c, QUALIFIER + "getNavigator\\s*\\(\\s*\\)\\s*\\.\\s*navigateTo\\s*\\(", mr -> use("UI") + ".getCurrent().navigate(");
            Set<String> navs = varsOf(Set.of("Navigator"));
            if (!navs.isEmpty()) c = rx(c, "\\b(?:" + alt(navs) + ")\\s*\\.\\s*navigateTo\\s*\\(", mr -> use("UI") + ".getCurrent().navigate(");
            c = rx(c, "\\bPage\\s*\\.\\s*getCurrent\\s*\\(\\s*\\)\\s*\\.\\s*getWebBrowser\\s*\\(\\s*\\)", mr -> use("VaadinSession") + ".getCurrent().getBrowser()");
            c = rx(c, QUALIFIER + "getPage\\s*\\(\\s*\\)\\s*\\.\\s*getWebBrowser\\s*\\(\\s*\\)", mr -> use("VaadinSession") + ".getCurrent().getBrowser()");
            c = c.replaceAll("\\bgetUI\\s*\\(\\s*\\)\\s*!=\\s*null", "getUI().isPresent()");
            c = c.replaceAll("\\bgetUI\\s*\\(\\s*\\)\\s*==\\s*null", "getUI().isEmpty()");
            c = c.replaceAll("\\bgetUI\\s*\\(\\s*\\)\\s*\\.\\s*(?!isPresent\\b|isEmpty\\b|ifPresent\\b|orElse|map\\b)", "getUI().orElseThrow().");
            if (wasOld("Page")) c = rx(c, "\\bPage\\s*\\.\\s*getCurrent\\s*\\(\\s*\\)", mr -> use("UI") + ".getCurrent().getPage()");
            if (wasOld("JavaScript")) {
                c = rx(c, "\\bJavaScript\\s*\\.\\s*getCurrent\\s*\\(\\s*\\)\\s*\\.\\s*execute\\s*\\(", mr -> use("UI") + ".getCurrent().getPage().executeJs(");
                c = rx(c, "\\bJavaScript\\s*\\.\\s*eval\\s*\\(", mr -> use("UI") + ".getCurrent().getPage().executeJs(");
            }
            return c;
        }

        String renameTypes(String c) {
            List<String> lines = new ArrayList<>(Arrays.asList(c.split("\n", -1)));
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.strip().startsWith("import ")) continue;
                for (TypeMapping tm : active)
                    for (String o : tm.oldFqns())
                        if (line.contains(o)) line = line.replaceAll(Pattern.quote(o) + "(?![\\w$])", Matcher.quoteReplacement(tm.removed() ? tm.oldSimple() : tm.newFqn()));
                lines.set(i, line);
            }
            c = String.join("\n", lines);
            for (TypeMapping tm : active) {
                if (tm.removed() || tm.oldSimple().equals(tm.newSimple())) continue;
                c = c.replaceAll("(?<![\\w.$])" + tm.oldSimple() + "(?![\\w$])", tm.newSimple());
            }
            return c;
        }

        // ---------- Hilfen ----------

        String cur = "";

        /** Findet Aufrufe (headRegex endet mit "(") inkl. passender schließender Klammer und lässt fn den Ersatz liefern. */
        String calls(String code, String headRegex, CallFn fn) {
            Matcher m = Pattern.compile(headRegex).matcher(code);
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            cur = code;
            while (pos <= code.length() && m.find(pos)) {
                int open = m.end() - 1;
                if (code.charAt(open) != '(') throw new IllegalStateException("Aufruf-Muster muss mit '(' enden: " + headRegex);
                int close = closingParen(code, open);
                String rep = null;
                if (close >= 0 && !isDeclaration(code, m.start()))
                    rep = fn.apply(m.toMatchResult(), splitArgs(code.substring(open + 1, close)), code.substring(m.start(), close + 1));
                if (rep == null) {
                    sb.append(code, pos, m.end());
                    pos = m.end();
                } else {
                    sb.append(code, pos, m.start()).append(rep);
                    pos = close + 1;
                }
            }
            sb.append(code, Math.min(pos, code.length()), code.length());
            return sb.toString();
        }

        /** Entfernt komplette Anweisungen wie "feld.setImmediate(true);". */
        String removeCalls(String c, String methods) {
            c = calls(c, QUALIFIER + "(?<![\\w$])(?:" + methods + ")\\s*\\(", (h, a, f) -> "\u0001");
            c = c.replaceAll("(?m)^[ \\t]*\u0001\\s*;[ \\t]*\\n", "");
            c = c.replaceAll("\u0001\\s*;", "");
            return c.replace("\u0001", "");
        }

        /** Unqualifizierter (bzw. this.) Methodenaufruf, keine Deklaration. */
        String bare(String c, String method, String repl) {
            final String code = c;
            return rx(c, "(?<![\\w$.])(?:this\\s*\\.\\s*)?" + Pattern.quote(method) + "(?=\\s*\\()",
                mr -> isDeclaration(code, mr.start()) ? mr.group() : repl);
        }

        /** Methodenname umbenennen (qualifiziert oder unqualifiziert), Deklarationen bleiben. */
        String ren(String c, String old, String neu) {
            final String code = c;
            return rx(c, "(?<![\\w$])" + Pattern.quote(old) + "(?=\\s*\\()", mr -> isDeclaration(code, mr.start()) ? mr.group() : neu);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Statische Hilfsfunktionen
    // ------------------------------------------------------------------------------------------------

    static boolean isOldVaadin(String n) {
        return n.startsWith("com.vaadin.") && !n.startsWith("com.vaadin.flow.") && !n.startsWith("com.vaadin.testbench.")
            && !n.startsWith("com.vaadin.external.") && !n.startsWith("com.vaadin.componentfactory.");
    }

    static String simpleName(String fqn) { return fqn.substring(fqn.lastIndexOf('.') + 1); }

    static Pattern word(String simple) { return Pattern.compile("(?<![\\w.$])" + Pattern.quote(simple) + "(?![\\w$])"); }

    static String rx(String code, String regex, Function<MatchResult, String> f) {
        return Pattern.compile(regex).matcher(code).replaceAll(mr -> Matcher.quoteReplacement(f.apply(mr)));
    }

    static String alt(Set<String> names) { return names.stream().map(Pattern::quote).collect(Collectors.joining("|")); }

    static boolean isStringLit(String a) { return a.strip().matches("\"@@S\\d+@@\""); }

    static String vert(String v) { return switch (v) { case "TOP" -> "START"; case "MIDDLE" -> "CENTER"; default -> "END"; }; }

    static String horiz(String h) { return switch (h) { case "LEFT" -> "START"; case "CENTER" -> "CENTER"; default -> "END"; }; }

    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    static int closingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    static int closingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    static boolean precededByDot(String code, int idx) {
        int i = idx - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        return i >= 0 && code.charAt(i) == '.';
    }

    /** true, wenn an idx eine Methodendeklaration beginnt (z.B. "void addComponent(" ). */
    static boolean isDeclaration(String code, int idx) {
        int i = idx - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        if (i < 0) return false;
        char ch = code.charAt(i);
        if (ch == '>' || ch == ']') return !code.substring(Math.max(0, i - 1), i + 1).equals("->");
        if (Character.isJavaIdentifierPart(ch)) {
            int e = i + 1;
            while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) i--;
            return !NOT_DECL_WORDS.contains(code.substring(i + 1, e));
        }
        return false;
    }

    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s.isBlank()) return out;
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i).strip()); start = i + 1; }
        }
        out.add(s.substring(start).strip());
        return out;
    }

    static List<String> splitTypes(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { out.add(s.substring(start, i).strip()); start = i + 1; }
        }
        String last = s.substring(start).strip();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    @SafeVarargs
    static Set<String> union(Set<String>... sets) {
        Set<String> r = new HashSet<>();
        for (Set<String> s : sets) r.addAll(s);
        return Set.copyOf(r);
    }
}
