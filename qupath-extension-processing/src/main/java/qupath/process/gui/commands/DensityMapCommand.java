/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.process.gui.commands;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import ij.CompositeImage;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectExpression;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener.Change;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import qupath.imagej.gui.IJExtension;
import qupath.imagej.gui.commands.density.DensityMapUI;
import qupath.imagej.tools.IJTools;
import qupath.lib.analysis.heatmaps.ColorModels;
import qupath.lib.analysis.heatmaps.ColorModels.ColorModelBuilder;
import qupath.lib.analysis.heatmaps.DensityMaps;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapBuilder;
import qupath.lib.analysis.heatmaps.DensityMaps.DensityMapType;
import qupath.lib.color.ColorMaps;
import qupath.lib.color.ColorMaps.ColorMap;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.ActionTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.images.servers.RenderedImageServer;
import qupath.lib.gui.images.stores.ColorModelRenderer;
import qupath.lib.gui.images.stores.ImageRenderer;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.gui.viewer.ImageInterpolation;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.PathObjectPredicates;
import qupath.lib.objects.PathObjectPredicates.PathObjectPredicate;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.scripting.QP;
import qupath.process.gui.commands.DensityMapCommand.MinMaxFinder.MinMax;


/**
 * Command for generating density maps from detections on an image.
 * 
 * @author Pete Bankhead
 */
public class DensityMapCommand implements Runnable {
	
	private final static Logger logger = LoggerFactory.getLogger(DensityMapCommand.class);
	
	private final static String title = "Density map";
	
	private QuPathGUI qupath;
	private DensityMapDialog dialog;
	
	/**
	 * Constructor.
	 * @param qupath
	 */
	public DensityMapCommand(QuPathGUI qupath) {
		this.qupath = qupath;
	}

	@Override
	public void run() {
		
		if (dialog == null) {
			dialog = new DensityMapDialog(qupath);
			if (qupath.getImageData() != null)
				dialog.updateDefaults(qupath.getImageData());
		}
		
		var stage = dialog.getStage();
		stage.setOnCloseRequest(e -> closeDialog());
		stage.show();
	}
	
	void closeDialog() {
		if (dialog != null) {
			dialog.deregister();
			dialog = null;
		}
	}
	
	
	/**
	 * Supported input objects.
	 */
	private static enum DensityMapObjects {
		
		DETECTIONS(PathObjectFilter.DETECTIONS_ALL),
		CELLS(PathObjectFilter.CELLS),
		POINT_ANNOTATIONS(
				PathObjectPredicates.filter(PathObjectFilter.ANNOTATIONS)
				.and(PathObjectPredicates.filter(PathObjectFilter.ROI_POINT)));
		
		private final PathObjectPredicate predicate;
		
		private DensityMapObjects(PathObjectFilter filter) {
			this(PathObjectPredicates.filter(filter));
		}
		
		private DensityMapObjects(PathObjectPredicate predicate) {
			this.predicate = predicate;
		}
		
		/**
		 * Get predicate to select objects of the desired type.
		 * @return
		 */
		public PathObjectPredicate getPredicate() {
			return predicate;
		}
		
		@Override
		public String toString() {
			switch(this) {
			case DETECTIONS:
				return "All detections";
			case CELLS:
				return "All cells";
			case POINT_ANNOTATIONS:
				return "Point annotations";
			default:
				throw new IllegalArgumentException("Unknown enum " + this);
			}
		}
		
	}
	
	
	static class DensityMapDialog {
		
		private QuPathGUI qupath;
				
		private final Stage stage;
		
		/**
		 * Core DensityMapBuilder (doesn't bother with colormodel)
		 */
		private final ObservableDensityMapBuilder densityMapBuilder = new ObservableDensityMapBuilder();

		/**
		 * Color model builder
		 */
		private final ObservableColorModelBuilder colorModelBuilder = new ObservableColorModelBuilder();
		
		/**
		 * DensityMapBuilder that combines the observable builder and colormodels
		 */
		private final ObjectExpression<DensityMapBuilder> combinedBuilder = Bindings.createObjectBinding(() -> {
			var b = densityMapBuilder.builder.get();
			var c = colorModelBuilder.builder.get();
			if (b == null || c == null)
				return b;
			var builder2 = DensityMaps.builder(b).colorModel(c);
			return builder2;
		}, densityMapBuilder.builder, colorModelBuilder.builder);

		private final ObjectProperty<ImageInterpolation> interpolation = new SimpleObjectProperty<>(ImageInterpolation.NEAREST);
				
		private HierarchyClassifierOverlayManager manager;
				
		private final double textFieldWidth = 80;
		private final double hGap = 5;
		private final double vGap = 5;
		
		/**
		 * Constructor.
		 * @param qupath
		 */
		public DensityMapDialog(QuPathGUI qupath) {
			this.qupath = qupath;
			
			var paneParams = buildAllObjectsPane(densityMapBuilder);
			var titledPaneParams = new TitledPane("Create density map", paneParams);
			titledPaneParams.setExpanded(true);
			titledPaneParams.setCollapsible(false);
			PaneTools.simplifyTitledPane(titledPaneParams, true);
			
			var paneDisplay = buildDisplayPane(colorModelBuilder);
			
			var titledPaneDisplay = new TitledPane("Customize appearance", paneDisplay);
			titledPaneDisplay.setExpanded(false);
			PaneTools.simplifyTitledPane(titledPaneDisplay, true);
			
			var pane = createGridPane();
			int row = 0;
			PaneTools.addGridRow(pane, row++, 0, null, titledPaneParams, titledPaneParams, titledPaneParams);			
			PaneTools.addGridRow(pane, row++, 0, null, titledPaneDisplay, titledPaneDisplay, titledPaneDisplay);

			
			var btnAutoUpdate = new ToggleButton("Auto-update");
			btnAutoUpdate.setSelected(densityMapBuilder.autoUpdate.get());
			btnAutoUpdate.setMaxWidth(Double.MAX_VALUE);
			btnAutoUpdate.selectedProperty().bindBidirectional(densityMapBuilder.autoUpdate);
			
			PaneTools.addGridRow(pane, row++, 0, "Automatically update the density map. "
					+ "Turn this off if changing parameters and heatmap generation is slow.", btnAutoUpdate, btnAutoUpdate, btnAutoUpdate);
			
			
			var savePane = DensityMapUI.createSaveDensityMapPane(qupath.projectProperty(), combinedBuilder, new SimpleStringProperty());
			PaneTools.addGridRow(pane, row++, 0, null, savePane, savePane, savePane);
			PaneTools.setToExpandGridPaneWidth(savePane);

			var buttonPane = buildButtonPane(qupath.imageDataProperty(), combinedBuilder);
			PaneTools.addGridRow(pane, row++, 0, null, buttonPane, buttonPane, buttonPane);
			PaneTools.setToExpandGridPaneWidth(btnAutoUpdate, buttonPane);

			pane.setPadding(new Insets(10));

			stage = new Stage();
			stage.setScene(new Scene(pane));
			stage.setResizable(false);
			stage.initOwner(qupath.getStage());
			stage.setTitle("Density map");
			
			// Update stage height when display options expanded/collapsed
			titledPaneDisplay.heightProperty().addListener((v, o, n) -> stage.sizeToScene());
			
			// Create new overlays for the viewers
			manager = new HierarchyClassifierOverlayManager(qupath, densityMapBuilder.builder, colorModelBuilder.colorModel, interpolation);
			manager.currentDensityMap.addListener((v, o, n) -> colorModelBuilder.updateDisplayRanges(n));
			stage.focusedProperty().addListener((v, o, n) -> {
				if (n)
					manager.updateViewers();
			});
		}
		
		
		private ObservableList<PathClass> createObservablePathClassList(PathClass... defaultClasses) {
			var available = qupath.getAvailablePathClasses();
			if (defaultClasses.length == 0)
				return available;
			var list = FXCollections.observableArrayList(defaultClasses);
			available.addListener((Change<? extends PathClass> c) -> updateList(list, available, defaultClasses));
			updateList(list, available, defaultClasses);
			return list;
		}
		
		private static void updateList(ObservableList<PathClass> mainList, ObservableList<PathClass> originalList, PathClass... additionalItems) {
			Set<PathClass> temp = new LinkedHashSet<>();
			for (var t : additionalItems)
				temp.add(t);
			temp.addAll(originalList);
			mainList.setAll(temp);
		}
		
		
		private static Pane buildButtonPane(ObjectExpression<ImageData<BufferedImage>> imageData, ObjectExpression<DensityMapBuilder> builder) {
						
			var actionHotspots = createDensityMapAction("Find hotspots", imageData, builder, new HotspotFinder(),
					"Find the hotspots in the density map with highest values");
			var btnHotspots = ActionTools.createButton(actionHotspots, false);

			var actionThreshold = createDensityMapAction("Threshold", imageData, builder, new ContourTracer(),
					"Threshold to identify high-density regions");
			var btnThreshold = ActionTools.createButton(actionThreshold, false);

			var actionExport = createDensityMapAction("Export map", imageData, builder, new DensityMapExporter(),
					"Export the density map as an image");
			var btnExport = ActionTools.createButton(actionExport, false);

			var buttonPane = PaneTools.createColumnGrid(btnHotspots, btnThreshold, btnExport);
//			buttonPane.setHgap(hGap);
			PaneTools.setToExpandGridPaneWidth(btnHotspots, btnExport, btnThreshold);
			return buttonPane;
		}
		
		
		private Pane buildAllObjectsPane(ObservableDensityMapBuilder params) {
			ComboBox<DensityMapObjects> comboObjectType = new ComboBox<>();
			comboObjectType.getItems().setAll(DensityMapObjects.values());
			comboObjectType.getSelectionModel().select(DensityMapObjects.DETECTIONS);
			params.allObjectTypes.bind(comboObjectType.getSelectionModel().selectedItemProperty());

			ComboBox<PathClass> comboAllObjects = new ComboBox<>(createObservablePathClassList(ANY_CLASS));
			comboAllObjects.setButtonCell(GuiTools.createCustomListCell(p -> classificationText(p)));
			comboAllObjects.setCellFactory(c -> GuiTools.createCustomListCell(p -> classificationText(p)));
			params.allObjectClass.bind(comboAllObjects.getSelectionModel().selectedItemProperty());
			comboAllObjects.getSelectionModel().selectFirst();
			
			ComboBox<PathClass> comboPrimary = new ComboBox<>(createObservablePathClassList(ANY_CLASS, ANY_POSITIVE_CLASS));
			comboPrimary.setButtonCell(GuiTools.createCustomListCell(p -> classificationText(p)));
			comboPrimary.setCellFactory(c -> GuiTools.createCustomListCell(p -> classificationText(p)));
			params.densityObjectClass.bind(comboPrimary.getSelectionModel().selectedItemProperty());
			comboPrimary.getSelectionModel().selectFirst();
			
			ComboBox<DensityMapType> comboDensityType = new ComboBox<>();
			comboDensityType.getItems().setAll(DensityMapType.values());
			comboDensityType.getSelectionModel().select(DensityMapType.SUM);
			params.densityType.bind(comboDensityType.getSelectionModel().selectedItemProperty());
			
			var pane = createGridPane();
			int row = 0;
			
			var labelObjects = createTitleLabel("Choose all objects to include");
			PaneTools.addGridRow(pane, row++, 0, null, labelObjects, labelObjects, labelObjects);
			
			PaneTools.addGridRow(pane, row++, 0, "Select objects used to generate the density map.\n"
					+ "Use 'All detections' to include all detection objects (including cells and tiles).\n"
					+ "Use 'All cells' to include cell objects only.\n"
					+ "Use 'Point annotations' to use annotated points rather than detections.",
					new Label("Object type"), comboObjectType, comboObjectType);
			
			PaneTools.addGridRow(pane, row++, 0, "Select object classifications to include.\n"
					+ "Use this to filter out detections that should not contribute to the density map at all.\n"
					+ "For example, this can be used to selectively consider tumor cells and ignore everything else.\n"
					+ "If used in combination with 'Density class' and 'Density type: Objects %', the 'Density class' defines the numerator and the 'Object class' defines the denominator.",
					new Label("Main class"), comboAllObjects, comboAllObjects);

			var labelDensities = createTitleLabel("Define density map");
			PaneTools.addGridRow(pane, row++, 0, null, labelDensities);
			
			PaneTools.addGridRow(pane, row++, 0, "Calculate the density of objects containing a specified classification.\n"
					+ "If used in combination with 'Object class' and 'Density type: Objects %', the 'Density class' defines the numerator and the 'Object class' defines the denominator.\n"
					+ "For example, choose 'Object class: Tumor', 'Density class: Positive' and 'Density type: Objects %' to define density as the proportion of tumor cells that are positive.",
					new Label("Secondary class"), comboPrimary, comboPrimary);
			
			PaneTools.addGridRow(pane, row++, 0, "Select method of normalizing densities.\n"
					+ "Choose whether to show raw counts, or normalize densities by area or the number of objects locally.\n"
					+ "This can be used to distinguish between the total number of objects in an area with a given classification, "
					+ "and the proportion of objects within the area with that classification.\n"
					+ "Gaussian weighting gives a smoother result, but it can be harder to interpret.",
					new Label("Density type"), comboDensityType, comboDensityType);
			
			
			var sliderRadius = new Slider(0, 1000, params.radius.get());
			sliderRadius.valueProperty().bindBidirectional(params.radius);
			initializeSliderSnapping(sliderRadius, 50, 1, 0.1);
			var tfRadius = createTextField();
			
			boolean expandSliderLimits = true;
			
			GuiTools.bindSliderAndTextField(sliderRadius, tfRadius, expandSliderLimits, 2);
			GuiTools.installRangePrompt(sliderRadius);
			PaneTools.addGridRow(pane, row++, 0, "Select smoothing radius used to calculate densities.\n"
					+ "This is defined in calibrated pixel units (e.g. µm if available).", new Label("Density radius"), sliderRadius, tfRadius);
			
			PaneTools.setToExpandGridPaneWidth(comboObjectType, comboPrimary, comboAllObjects, comboDensityType, sliderRadius);

			return pane;
		}
		
		
		private Pane buildDisplayPane(ObservableColorModelBuilder displayParams) {
			
			var comboColorMap = new ComboBox<ColorMap>();
			comboColorMap.getItems().setAll(ColorMaps.getColorMaps().values());
			if (comboColorMap.getSelectionModel().getSelectedItem() == null)
				comboColorMap.getSelectionModel().select(ColorMaps.getDefaultColorMap());
			displayParams.colorMap.bind(comboColorMap.getSelectionModel().selectedItemProperty());
			
			var comboInterpolation = new ComboBox<ImageInterpolation>();
			
			var paneDisplay = createGridPane();
			
			int rowDisplay = 0;
			
			// Colormap
			var labelColormap = createTitleLabel("Colors");
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, "Customize the colors of the density map", labelColormap, labelColormap, labelColormap);			
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, "Choose the colormap to use for display", new Label("Colormap"), comboColorMap, comboColorMap);

			var spinnerGrid = new GridPane();
			int spinnerRow = 0;
			
			var spinnerMin = createSpinner(displayParams.minDisplay, 10);
			var spinnerMax = createSpinner(displayParams.maxDisplay, 10);
			spinnerGrid.setHgap(hGap);
			spinnerGrid.setVgap(vGap);
			
			var toggleAuto = new ToggleButton("Auto");
			toggleAuto.selectedProperty().bindBidirectional(displayParams.autoUpdateDisplayRange);
			spinnerMin.disableProperty().bind(toggleAuto.selectedProperty());
			spinnerMax.disableProperty().bind(toggleAuto.selectedProperty());
			
			PaneTools.addGridRow(spinnerGrid, spinnerRow++, 0, null, new Label("Min"), spinnerMin, new Label("Max"), spinnerMax, toggleAuto);
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, 
					"Set the min/max density values for the colormap.\n"
					+ "This determines how the colors in the colormap relate to density values.\n"
					+ "Choose 'Auto' to assign colors based upon the full range of the values in the current density map.",
					new Label("Range"), spinnerGrid, spinnerGrid);

			// Alpha
			var labelAlpha = createTitleLabel("Opacity");
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, "Customize the opacity (alpha) of the density map.\n"
					+ "Note that this is based upon the count of all objects.", labelAlpha, labelAlpha, labelAlpha);			
			
			var spinnerGridAlpha = new GridPane();
			spinnerRow = 0;
			
			var spinnerMinAlpha = createSpinner(displayParams.minAlpha, 10);
			var spinnerMaxAlpha = createSpinner(displayParams.maxAlpha, 10);
			spinnerGridAlpha.setHgap(hGap);
			spinnerGridAlpha.setVgap(vGap);
			
			var toggleAutoAlpha = new ToggleButton("Auto");
			toggleAutoAlpha.selectedProperty().bindBidirectional(displayParams.autoUpdateAlphaRange);
			spinnerMinAlpha.disableProperty().bind(toggleAutoAlpha.selectedProperty());
			spinnerMaxAlpha.disableProperty().bind(toggleAutoAlpha.selectedProperty());

			PaneTools.addGridRow(spinnerGridAlpha, spinnerRow++, 0, null, new Label("Min"), spinnerMinAlpha, new Label("Max"), spinnerMaxAlpha, toggleAutoAlpha);
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0,
					"Set the min/max density values for the opacity range.\n"
					+ "This can used in combination with 'Gamma' to adjust the opacity according to the "
					+ "number or density of objects. Use 'Auto' to use the full display range for the current image.",
					new Label("Range"), spinnerGridAlpha, spinnerGridAlpha);

			var sliderGamma = new Slider(0, 5, displayParams.gamma.get());
			sliderGamma.valueProperty().bindBidirectional(displayParams.gamma);
			initializeSliderSnapping(sliderGamma, 0.1, 1, 0.1);
			var tfGamma = createTextField();
			GuiTools.bindSliderAndTextField(sliderGamma, tfGamma, false, 1);
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0,
					"Control how the opacity of the density map changes between min & max values.\n"
					+ "Choose zero for an opaque map.", new Label("Gamma"), sliderGamma, tfGamma);

			// Interpolation
			var labelSmoothness = createTitleLabel("Smoothness");
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, "Customize density map interpolation (visual smoothness)", labelSmoothness);			
			
			PaneTools.addGridRow(paneDisplay, rowDisplay++, 0, "Choose how the density map should be interpolated.\n"
					+ "This impacts the visual smoothness, especially if the density radius is small and the image is viewed while zoomed in.", new Label("Interpolation"), comboInterpolation, comboInterpolation);

			comboInterpolation.getItems().setAll(ImageInterpolation.values());
			comboInterpolation.getSelectionModel().select(ImageInterpolation.NEAREST);
			interpolation.bind(comboInterpolation.getSelectionModel().selectedItemProperty());

			PaneTools.setToExpandGridPaneWidth(comboColorMap, comboInterpolation, sliderGamma);
			
			return paneDisplay;
		}
		
		Spinner<Double> createSpinner(ObjectProperty<Double> property, double step) {
			var spinner = GuiTools.createDynamicStepSpinner(0, Double.MAX_VALUE, 1, 0.1);
			property.bindBidirectional(spinner.getValueFactory().valueProperty());
			spinner.setEditable(true);
			spinner.getEditor().setPrefColumnCount(6);
			GuiTools.restrictTextFieldInputToNumber(spinner.getEditor(), true);
			return spinner;
		}
		
		Label createTitleLabel(String text) {
			var label = new Label(text);
			label.setStyle("-fx-font-weight: bold;");
			label.setMaxWidth(Double.MAX_VALUE);
			return label;
		}
		
		
		/**
		 * Create a {@link GridPane} with standard gaps.
		 * @return
		 */
		GridPane createGridPane() {
			var pane = new GridPane();
			pane.setVgap(vGap);
			pane.setHgap(hGap);
			return pane;
		}
		
		
		/**
		 * Create a {@link TextField} with a standard width;
		 * @return
		 */
		TextField createTextField() {
			var textField = new TextField();
			textField.setMaxWidth(textFieldWidth);
			return textField;
		}
				
		
		/**
		 * Update default parameters with a specified ImageData.
		 * This gives better starting values.
		 * @param imageData
		 */
		boolean updateDefaults(ImageData<BufferedImage> imageData) {
			if (imageData == null)
				return false;
			var server = imageData.getServer();
			double pixelSize = Math.round(server.getPixelCalibration().getAveragedPixelSize().doubleValue() * 10);
			pixelSize *= 100;
//			if (server.nResolutions() > 1)
//				pixelSize *= 10;
			pixelSize = Math.min(pixelSize, Math.min(server.getHeight(), server.getWidth())/20.0);
			densityMapBuilder.radius.set(pixelSize);
			return true;
		}
		
		
		private void initializeSliderSnapping(Slider slider, double blockIncrement, double majorTicks, double minorTicks) {
			slider.setBlockIncrement(blockIncrement);
			slider.setMajorTickUnit(majorTicks);
			slider.setMinorTickCount((int)Math.round(majorTicks / minorTicks) - 1);
			slider.setSnapToTicks(true);
		}
		
		
		private String classificationText(PathClass pathClass) {
			if (pathClass == null)
				pathClass = PathClassFactory.getPathClassUnclassified();
			if (pathClass == ANY_CLASS)
				return "Any";
			if (pathClass == ANY_POSITIVE_CLASS)
				return "Positive (inc. 1+, 2+, 3+)";
			return pathClass.toString();
		}

		
		/**
		 * Deregister listeners. This should be called when the stage is closed if it will not be used again.
		 */
		public void deregister() {
			manager.shutdown();
		}
		
		public Stage getStage() {
			return stage;
		}
		
		
	}
	
	
	static class MinMaxFinder {
		
		private static Map<String, List<MinMax>> cache = Collections.synchronizedMap(new HashMap<>());
		
	
		static class MinMax {
			
			private float minValue = Float.POSITIVE_INFINITY;
			private float maxValue = Float.NEGATIVE_INFINITY;
			
			void update(float val) {
				if (Float.isNaN(val))
					return;
				if (val < minValue)
					minValue = val;
				if (val > maxValue)
					maxValue = val;
			}
			
		}
		
		/**
		 * Get the minimum and maximum values for all pixels across all channels of an image.
		 * Note that this will use a cached value, therefore it is assumed that the server cannot change.
		 * 
		 * @param server server containing pixels
		 * @param countBand optional band that can be thresholded and used for masking; if -1, then the same band is used for counts
		 * @param minCount minimum value for pixels to be included
		 * @return
		 * @throws IOException 
		 */
		private static List<MinMax> getMinMax(ImageServer<BufferedImage> server, int countBand, float minCount) throws IOException {
			String key = server.getPath() + "?count=" + countBand + "&minCount=" + minCount;
			var minMax = cache.get(key);
			if (minMax == null) {
				minMax = calculateMinMax(server, countBand, minCount);
				cache.put(key, minMax);
			}
			return minMax;
		}
		
		private static List<MinMax> calculateMinMax(ImageServer<BufferedImage> server, int countBand, float minCount) throws IOException {
			var tiles = getAllTiles(server, 0, false);
			if (tiles == null)
				return null;
			// Sometimes we use the
			boolean countsFromSameBand = countBand < 0;
			int nBands = server.nChannels();
			List<MinMax> results = IntStream.range(0, nBands).mapToObj(i -> new MinMax()).collect(Collectors.toList());
			float[] pixels = null;
			float[] countPixels = null;
			for (var img : tiles.values()) {
				var raster = img.getRaster();
				int w = raster.getWidth();
				int h = raster.getHeight();
				if (pixels == null || pixels.length < w*h) {
					pixels = new float[w*h];
					if (!countsFromSameBand)
						countPixels = new float[w*h];
				}
				countPixels = !countsFromSameBand ? raster.getSamples(0, 0, w, h, countBand, countPixels) : null;
				for (int band = 0; band < nBands; band++) {
					var minMax = results.get(band);
					pixels = raster.getSamples(0, 0, w, h, band, pixels);
					if (countsFromSameBand) {
						for (int i = 0; i < w*h; i++) {
							if (pixels[i] > minCount)
								minMax.update(pixels[i]);
						}					
					} else {
						for (int i = 0; i < w*h; i++) {
							if (countPixels[i] > minCount)
								minMax.update(pixels[i]);
						}
					}
				}
			}
			return Collections.unmodifiableList(results);
		}
		
		private static Map<RegionRequest, BufferedImage> getAllTiles(ImageServer<BufferedImage> server, int level, boolean ignoreInterrupts) throws IOException {
			Map<RegionRequest, BufferedImage> map = new LinkedHashMap<>();
			var tiles = server.getTileRequestManager().getTileRequestsForLevel(level);
	    	for (var tile : tiles) {
	    		if (!ignoreInterrupts && Thread.currentThread().isInterrupted())
	    			return null;
	    		var region = tile.getRegionRequest();
	    		if (server.isEmptyRegion(region))
	    			continue;
	    		var imgTile = server.readBufferedImage(region);
	    		if (imgTile != null)
	    			map.put(region, imgTile);
	    	}
	    	return map;
		}
		
	}
	
	/**
	 * Ignore classification (accept all objects).
	 * Generated with a UUID for uniqueness, and because it should not be serialized.
	 */
	public static final PathClass ANY_CLASS = PathClassFactory.getPathClass(UUID.randomUUID().toString());
	
	/**
	 * Accept any positive classification, including 1+, 2+, 3+.
	 * Generated with a UUID for uniqueness, and because it should not be serialized.
	 */
	public static final PathClass ANY_POSITIVE_CLASS = PathClassFactory.getPathClass(UUID.randomUUID().toString());

	
	/**
	 * Encapsulate the parameters needed to generate a density map in a JavaFX-friendly way.
	 */
	static class ObservableDensityMapBuilder {
		
		private ObjectProperty<DensityMapObjects> allObjectTypes = new SimpleObjectProperty<>(DensityMapObjects.DETECTIONS);
		private ObjectProperty<PathClass> allObjectClass = new SimpleObjectProperty<>(ANY_CLASS);
		private ObjectProperty<PathClass> densityObjectClass = new SimpleObjectProperty<>(ANY_CLASS);
		private ObjectProperty<DensityMapType> densityType = new SimpleObjectProperty<>(DensityMapType.SUM);
		
		private DoubleProperty radius = new SimpleDoubleProperty(10.0);
		
		/**
		 * Automatically update the density maps and overlays.
		 */
		private final BooleanProperty autoUpdate = new SimpleBooleanProperty(true);
		
		private final ObjectProperty<DensityMapBuilder> builder = new SimpleObjectProperty<>();

		private Gson gson = GsonTools.getInstance();
				
		ObservableDensityMapBuilder() {
			allObjectTypes.addListener((v, o, n) -> updateBuilder());
			allObjectClass.addListener((v, o, n) -> updateBuilder());
			densityObjectClass.addListener((v, o, n) -> updateBuilder());
			densityType.addListener((v, o, n) -> updateBuilder());
			radius.addListener((v, o, n) -> updateBuilder());
			autoUpdate.addListener((v, o, n) -> updateBuilder());
		}
		
		/**
		 * Update the classifier. Note that this can only be done if there is an active {@link ImageData}, 
		 * which is used to get pixel calibration information.
		 */
		private void updateBuilder() {
			if (!autoUpdate.get())
				return;
			// Only update the classifier if it is different from the current classifier
			// To test this, we rely upon the JSON representation
			var newBuilder = createBuilder();
			var currentBuilder = builder.get();
			if (newBuilder != null && !Objects.equals(newBuilder, currentBuilder)) {
				if (currentBuilder == null || !Objects.equals(gson.toJson(currentBuilder), gson.toJson(newBuilder)))
					builder.set(newBuilder);
			}
		}
		
		private PathObjectPredicate updatePredicate(PathObjectPredicate predicate, PathClass pathClass) {
			if (pathClass == ANY_CLASS)
				return predicate;
			PathObjectPredicate pathClassPredicate;
			if (pathClass == ANY_POSITIVE_CLASS)
				pathClassPredicate = PathObjectPredicates.positiveClassification(true);
			else if (pathClass == null || pathClass.getName() == null)
				pathClassPredicate = PathObjectPredicates.exactClassification((PathClass)null);
			else if (pathClass.isDerivedClass())
				pathClassPredicate = PathObjectPredicates.exactClassification(pathClass);
			else
				pathClassPredicate = PathObjectPredicates.containsClassification(pathClass.getName());
			return predicate == null ? pathClassPredicate : predicate.and(pathClassPredicate);
		}
		
		private DensityMapBuilder createBuilder() {
			// Determine all objects filter
			PathObjectPredicate allObjectsFilter = allObjectTypes.get().getPredicate();
			PathClass primaryClass = allObjectClass.get();
			allObjectsFilter = updatePredicate(allObjectsFilter, primaryClass);
			
			// Determine density objects filter
			var densityClass = densityObjectClass.get();
			PathObjectPredicate densityObjectsFilter = updatePredicate(null, densityClass);
			
			// Sometimes the density class is null - in which case we can't build
			if (densityClass == null)
				densityClass = ANY_CLASS;
			
			// Create map
			var builder = DensityMaps.builder(allObjectsFilter);
			
			builder.type(densityType.get());

			if (densityObjectsFilter != null) {
				String filterName;
				String densityClassName = densityClass.toString();
				if (densityClass == ANY_POSITIVE_CLASS)
					densityClassName = "Positive";
				if (primaryClass == null || primaryClass == PathClassFactory.getPathClassUnclassified())
					filterName = densityClassName + " %";
				else
					filterName = primaryClass.toString() + "+" + densityClassName + " %";
				builder.addDensities(filterName, densityObjectsFilter);
			}
			
			builder.radius(radius.get());
			return builder;
		}
	}
	
	
	
	/**
	 * Encapsulate the stuff we need to build an {@link ImageRenderer}.
	 */
	static class ObservableColorModelBuilder {
				
		private final ObjectProperty<ColorMap> colorMap = new SimpleObjectProperty<>();
		
		// Not observable, since the user can't adjust them (and we don't want unnecessary events fired)
		private int alphaCountBand = -1;
		
		// Because these will be bound to a spinner, we need an object property - 
		// and we can't use DoubleProperty(0.0).asObject() because of premature garbage collection 
		// breaking the binding
		private ObjectProperty<Double> minAlpha = new SimpleObjectProperty<>(0.0);
		private ObjectProperty<Double> maxAlpha = new SimpleObjectProperty<>(1.0);
		
		// Observable, so we can update them in the UI
		private final DoubleProperty gamma = new SimpleDoubleProperty(1.0);

		private final ObjectProperty<Double> minDisplay = new SimpleObjectProperty<>(0.0);
		private final ObjectProperty<Double> maxDisplay = new SimpleObjectProperty<>(1.0);
		
		private final BooleanProperty autoUpdateDisplayRange = new SimpleBooleanProperty(true);
		private final BooleanProperty autoUpdateAlphaRange = new SimpleBooleanProperty(true);
		
		private final ObjectProperty<ColorModelBuilder> builder = new SimpleObjectProperty<>();
		private final ObservableValue<ColorModel> colorModel = Bindings.createObjectBinding(() -> {
			var b = builder.get();
			return b == null ? null : b.build();
		}, builder);
		
		/*
		 * Flag to delay responding to all listeners when updating multiple properties
		 */
		private boolean updating = false;
		
		private ImageServer<BufferedImage> lastMap;
		
		ObservableColorModelBuilder() {
			colorMap.addListener((v, o, n) -> updateColorModel());

			minDisplay.addListener((v, o, n) -> updateColorModel());
			maxDisplay.addListener((v, o, n) -> updateColorModel());
			
			minAlpha.addListener((v, o, n) -> updateColorModel());
			maxAlpha.addListener((v, o, n) -> updateColorModel());
			gamma.addListener((v, o, n) -> updateColorModel());
			
			autoUpdateDisplayRange.addListener((v, o, n) -> {
				if (n)
					updateDisplayRanges(lastMap);
			});
			autoUpdateAlphaRange.addListener((v, o, n) -> {
				if (n)
					updateDisplayRanges(lastMap);
			});
		}
		
		private void updateDisplayRanges(ImageServer<BufferedImage> densityMapServer) {
			this.lastMap = densityMapServer;
			if (densityMapServer == null)
				return;
			
			assert Platform.isFxApplicationThread();
			
			try {
				updating = true;
				boolean autoUpdateSomething = autoUpdateDisplayRange.get() || autoUpdateAlphaRange.get();
				
				// If the last channel is 'counts', then it is used for normalization
				int alphaCountBand = -1;
				if (densityMapServer.getChannel(densityMapServer.nChannels()-1).getName().equals(DensityMaps.CHANNEL_ALL_OBJECTS))
					alphaCountBand = densityMapServer.nChannels()-1;
				
				// Compute min/max values if we need them
				List<MinMax> minMax = null;
				if (alphaCountBand > 0 || autoUpdateSomething) {
					try {
						minMax = MinMaxFinder.getMinMax(densityMapServer, alphaCountBand, 0);
					} catch (IOException e) {
						logger.warn("Error setting display ranges: " + e.getLocalizedMessage(), e);
					}
				}
				
				// Determine min/max values for alpha in count channel, if needed
				if (autoUpdateAlphaRange.get()) {
					minAlpha.set(1e-6);
					int band = Math.max(alphaCountBand, 0); 
					maxAlpha.set(Math.max(minAlpha.get(), (double)minMax.get(band).maxValue));
				}
				this.alphaCountBand = alphaCountBand;
				
				double maxDisplayValue = minMax == null ? maxDisplay.get() : minMax.get(0).maxValue;
				double minDisplayValue = 0;
				if (autoUpdateDisplayRange.get()) {
					minDisplay.set(minDisplayValue);
					maxDisplay.set(maxDisplayValue);
				}
			} finally {
				updating = false;
			}
			updateColorModel();
		}
		
		private void updateColorModel() {
			// Stop events if multiple updates in progress
			if (updating)
				return;
			
			int band = 0;
			builder.set(ColorModels.createColorModelBuilder(
					ColorModels.createBand(colorMap.get().getName(), band, minDisplay.get(), maxDisplay.get()),
					ColorModels.createBand(null, alphaCountBand, minAlpha.get(), maxAlpha.get(), gamma.get()))
					);
		}
		
		
	}
	
	
	
	static class HotspotFinder implements BiConsumer<ImageData<BufferedImage>, DensityMapBuilder> {
		
		private ParameterList paramsHotspots = new ParameterList()
				.addIntParameter("nHotspots", "Number of hotspots to find", 1, null, "Specify the number of hotspots to identify; hotspots are peaks in the density map")
				.addDoubleParameter("minDensity", "Min object count", 1, null, "Specify the minimum density of objects to accept within a hotspot")
				.addBooleanParameter("allowOverlaps", "Allow overlapping hotspots", false, "Allow hotspots to overlap; if false, peaks are discarded if the hotspot radius overlaps with a 'hotter' hotspot")
				.addBooleanParameter("deletePrevious", "Delete existing hotspots", true, "Delete existing hotspot annotations with the same classification")
				;
		
		@Override
		public void accept(ImageData<BufferedImage> imageData, DensityMapBuilder builder) {
			
			if (imageData == null || builder == null) {
				Dialogs.showErrorMessage(title, "No density map found!");
				return;
			}
			
			if (!Dialogs.showParameterDialog(title, paramsHotspots))
				return;
			
			double radius = builder.buildParameters().getRadius();
			
			int n = paramsHotspots.getIntParameterValue("nHotspots");
			double minDensity = paramsHotspots.getDoubleParameterValue("minDensity");
			boolean allowOverlapping = paramsHotspots.getBooleanParameterValue("allowOverlaps");
			boolean deleteExisting = paramsHotspots.getBooleanParameterValue("deletePrevious");
						
			int channel = 0; // TODO: Allow changing channel (if multiple channels available)
			
			var hierarchy = imageData.getHierarchy();
			var selected = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());
			if (selected.isEmpty())
				selected.add(imageData.getHierarchy().getRootObject());
			
			try {
				var server = builder.buildServer(imageData);
				
				// Remove existing hotspots with the same classification
				PathClass hotspotClass = getHotpotClass(server.getChannel(channel).getName());
				if (deleteExisting) {
					var hotspotClass2 = hotspotClass;
					var existing = hierarchy.getAnnotationObjects().stream()
							.filter(a -> a.getPathClass() == hotspotClass2)
							.collect(Collectors.toList());
					hierarchy.removeObjects(existing, true);
				}
				
				DensityMaps.findHotspots(hierarchy, server, channel, selected, n, radius, minDensity, allowOverlapping, hotspotClass);
			} catch (IOException e) {
				Dialogs.showErrorNotification(title, e);
			}
		}
		
		
	}
	
	
	
	static Action createDensityMapAction(String text, ObjectExpression<ImageData<BufferedImage>> imageData, ObjectExpression<DensityMapBuilder> builder,
			BiConsumer<ImageData<BufferedImage>, DensityMapBuilder> consumer, String tooltip) {
		var action = new Action(text, e -> consumer.accept(imageData.get(), builder.get()));
		if (tooltip != null)
			action.setLongText(tooltip);
		action.disabledProperty().bind(builder.isNull().or(imageData.isNull()));
		return action;
	}
	
	
	
	static class ContourTracer implements BiConsumer<ImageData<BufferedImage>, DensityMapBuilder> {
		
		private ParameterList paramsTracing = new ParameterList()
				.addDoubleParameter("threshold", "Density threshold", 0.5, null, "Define the density threshold to detection regions")
				.addBooleanParameter("split", "Split regions", false, "Split disconnected regions into separate annotations");
		
		@Override
		public void accept(ImageData<BufferedImage> imageData, DensityMapBuilder builder) {
			
			if (imageData == null) {
				Dialogs.showErrorMessage(title, "No image available!");
				return;
			}

			if (builder == null) {
				Dialogs.showErrorMessage(title, "No density map is available!");
				return;
			}
						
			if (!Dialogs.showParameterDialog("Trace contours from density map", paramsTracing))
				return;
			
			var threshold = paramsTracing.getDoubleParameterValue("threshold");
			boolean doSplit = paramsTracing.getBooleanParameterValue("split");
			
			var hierarchy = imageData.getHierarchy();
			var selected = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());
			if (selected.isEmpty())
				selected.add(imageData.getHierarchy().getRootObject());
			
			int channel = 0;
			var server = builder.buildServer(imageData);
			PathClass hotspotClass = getHotpotClass(server.getMetadata().getChannels().get(channel).getName());
			DensityMaps.traceContours(hierarchy, server, channel, selected, threshold, doSplit, hotspotClass);
		}
		
	}
	
	static class DensityMapExporter implements BiConsumer<ImageData<BufferedImage>, DensityMapBuilder> {
		
		@Override
		public void accept(ImageData<BufferedImage> imageData, DensityMapBuilder builder) {
			
			if (imageData == null || builder == null) {
				Dialogs.showErrorMessage(title, "No density map is available!");
				return;
			}
			
			var densityMapServer = builder.buildServer(imageData);
			
			var dialog = new Dialog<ButtonType>();
			dialog.setTitle(title);
			dialog.setHeaderText("How do you want to export the density map?");
			dialog.setContentText("Choose 'Raw values' of 'Send to ImageJ' if you need the original counts, or 'Color overlay' if you want to keep the same visual appearance.");
			var btOrig = new ButtonType("Raw values");
			var btColor = new ButtonType("Color overlay");
			var btImageJ = new ButtonType("Send to ImageJ");
			dialog.getDialogPane().getButtonTypes().setAll(btOrig, btColor, btImageJ, ButtonType.CANCEL);
			
			var response = dialog.showAndWait().orElse(ButtonType.CANCEL);
			try {
				if (btOrig.equals(response)) {
					promptToSaveRawImage(densityMapServer);
				} else if (btColor.equals(response)) {
					promptToSaveColorImage(densityMapServer, null); // Counting on color model being set!
				} else if (btImageJ.equals(response)) {
					sendToImageJ(densityMapServer);
				}
			} catch (IOException e) {
				Dialogs.showErrorNotification(title, e);
			}
		}
		
		private void promptToSaveRawImage(ImageServer<BufferedImage> densityMap) throws IOException {
			var file = Dialogs.promptToSaveFile(title, null, null, "ImageJ tif", ".tif");
			if (file != null)
				QP.writeImage(densityMap, file.getAbsolutePath());
		}

		private void promptToSaveColorImage(ImageServer<BufferedImage> densityMap, ColorModel colorModel) throws IOException {
			var server = RenderedImageServer.createRenderedServer(densityMap, new ColorModelRenderer(colorModel));
			File file;
			if (server.nResolutions() == 1 && server.nTimepoints() == 1 && server.nZSlices() == 1)
				file = Dialogs.promptToSaveFile(title, null, null, "PNG", ".png");
			else
				file = Dialogs.promptToSaveFile(title, null, null, "ImageJ tif", ".tif");
			if (file != null) {
				QP.writeImage(server, file.getAbsolutePath());
			}
		}

		private void sendToImageJ(ImageServer<BufferedImage> densityMap) throws IOException {
			if (densityMap == null) {
				Dialogs.showErrorMessage(title, "No density map is available!");
				return;
			}
			IJExtension.getImageJInstance();
			var imp = IJTools.extractHyperstack(densityMap, null);
			if (imp instanceof CompositeImage)
				((CompositeImage)imp).resetDisplayRanges();
			imp.show();
		}
		
	}
	
	/**
	 * Get a classification to use for hotspots based upon an image channel / classification name.
	 * @param channelName
	 * @return
	 */
	static PathClass getHotpotClass(String channelName) {		
		PathClass baseClass = channelName == null || channelName.isBlank() || DensityMaps.CHANNEL_ALL_OBJECTS.equals(channelName) ? null : PathClassFactory.getPathClass(channelName);
		return DensityMaps.getHotspotClass(baseClass);
		
	}
	
		
	/**
	 * Manage a single {@link PixelClassificationOverlay} that may be applied across multiple viewers.
	 * This is written to potentially support different kinds of classifier that require updates on a hierarchy change.
	 * When the classifier changes, it is applied to all viewers in a background thread and then the viewers repainted when complete 
	 * (to avoid flickering). As such it's assumed classifiers are all quite fast to apply and don't have large memory requirements.
	 */
	static class HierarchyClassifierOverlayManager implements PathObjectHierarchyListener, QuPathViewerListener {
		
		private final QuPathGUI qupath;
		
		private final Set<QuPathViewer> currentViewers = new HashSet<>();
		
		private ObservableValue<ImageRenderer> renderer;
		private final PixelClassificationOverlay overlay;
		private final ObservableValue<DensityMapBuilder> builder;
		// Cache a server
		private Map<ImageData<BufferedImage>, ImageServer<BufferedImage>> classifierServerMap = Collections.synchronizedMap(new HashMap<>());
		
		private ExecutorService pool = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("density-maps", true));;
		private Map<QuPathViewer, Future<?>> tasks = Collections.synchronizedMap(new HashMap<>());
		
		private ObjectProperty<ImageServer<BufferedImage>> currentDensityMap = new SimpleObjectProperty<>();
		
		HierarchyClassifierOverlayManager(QuPathGUI qupath, ObservableValue<DensityMapBuilder> builder, ObservableValue<ColorModel> colorModel, ObservableValue<ImageInterpolation> interpolation) {
			this.qupath = qupath;
			this.builder = builder;
			var options = qupath.getOverlayOptions();
			renderer = Bindings.createObjectBinding(() -> new ColorModelRenderer(colorModel.getValue()), colorModel);
			
			overlay = PixelClassificationOverlay.create(options, classifierServerMap, renderer.getValue());
			updateViewers();
			overlay.interpolationProperty().bind(interpolation);
			overlay.interpolationProperty().addListener((v, o, n) -> qupath.repaintViewers());
			
			overlay.rendererProperty().bind(renderer);
			renderer.addListener((v, o, n) -> qupath.repaintViewers());
			
			overlay.setLivePrediction(true);
			builder.addListener((v, o, n) -> updateDensityServers());
			updateDensityServers();
		}
		
		/**
		 * Ensure the overlay is present on all viewers
		 */
		void updateViewers() {
			for (var viewer : qupath.getViewers()) {
				viewer.setCustomPixelLayerOverlay(overlay);
				if (!currentViewers.contains(viewer)) {
					viewer.addViewerListener(this);
					var hierarchy = viewer.getHierarchy();
					if (hierarchy != null)
						hierarchy.addPathObjectListener(this);
					currentViewers.add(viewer);
					updateDensityServer(viewer);
				}
			}
		}
		

		@Override
		public void hierarchyChanged(PathObjectHierarchyEvent event) {
			if (event.isChanging())
				return;
			qupath.getViewers().stream().filter(v -> v.getHierarchy() == event.getHierarchy()).forEach(v -> updateDensityServer(v));
		}

		@Override
		public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> imageDataOld,
				ImageData<BufferedImage> imageDataNew) {
			
			if (imageDataOld != null)
				imageDataOld.getHierarchy().removePathObjectListener(this);
			
			if (imageDataNew != null) {
				imageDataNew.getHierarchy().addPathObjectListener(this);
			}
			updateDensityServer(viewer);
		}
		
		private void updateDensityServers() {
			classifierServerMap.clear(); // TODO: Check if this causes any flickering
			for (var viewer : qupath.getViewers())
				updateDensityServer(viewer);
		}
		
		private void updateDensityServer(QuPathViewer viewer) {
			if (Platform.isFxApplicationThread()) {
				synchronized (tasks) {
					var task = tasks.get(viewer);
					if (task != null && !task.isDone())
						task.cancel(true);
					if (!pool.isShutdown())
						task = pool.submit(() -> updateDensityServer(viewer));
					tasks.put(viewer, task);
				}
				return;
			}
			var imageData = viewer.getImageData();
			var builder = this.builder.getValue();
			if (imageData == null || builder == null) {
				classifierServerMap.remove(imageData);
			} else {
				if (Thread.interrupted())
					return;
				// Create server with a unique ID, because it may change with the object hierarchy & we don't want caching to mask this
				var tempServer = builder.buildServer(imageData);
				if (Thread.interrupted())
					return;
				// If the viewer is the main viewer, update the current map (which can then impact colors)
				if (viewer == qupath.getViewer())
					Platform.runLater(() -> currentDensityMap.set(tempServer));
				classifierServerMap.put(imageData, tempServer);
				if (viewer == qupath.getViewer())
					currentDensityMap.set(tempServer);
				viewer.repaint();
			}
		}

		@Override
		public void visibleRegionChanged(QuPathViewer viewer, Shape shape) {}

		@Override
		public void selectedObjectChanged(QuPathViewer viewer, PathObject pathObjectSelected) {}

		@Override
		public void viewerClosed(QuPathViewer viewer) {
			imageDataChanged(viewer, viewer.getImageData(), null);
			viewer.removeViewerListener(this);
			currentViewers.remove(viewer);
		}

		public void shutdown() {
			tasks.values().stream().forEach(t -> t.cancel(true));
			pool.shutdown();
			for (var viewer : qupath.getViewers()) {
				imageDataChanged(viewer, viewer.getImageData(), null);
				viewer.removeViewerListener(this);
				if (viewer.getCustomPixelLayerOverlay() == overlay)
					viewer.resetCustomPixelLayerOverlay();				
			}
			if (overlay != null) {
				overlay.stop();
			}
		}
		
		
	}
	
	

}
