/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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

package qupath.lib.gui.panes;

import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Locale.Category;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import javafx.scene.control.TitledPane;
import org.controlsfx.control.MasterDetailPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Label;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.TextAlignment;
import qupath.fx.utils.FXUtils;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.color.ColorDeconvolutionHelper;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

/**
 * A panel used for displaying basic info about an image, e.g. its path, width, height, pixel size etc.
 * <p>
 * It also includes displaying color deconvolution vectors for RGB brightfield images.
 * 
 * @author Pete Bankhead
 *
 */
public class ImageDetailsPane implements ChangeListener<ImageData<BufferedImage>>, PropertyChangeListener {

	private static final Logger logger = LoggerFactory.getLogger(ImageDetailsPane.class);

	private ImageData<BufferedImage> imageData;

	private StackPane pane = new StackPane();

	private TableView<ImageDetailRow> table = new TableView<>();
	private ListView<String> listAssociatedImages = new ListView<>();

	private Map<String, SimpleImageViewer> associatedImageViewers = new HashMap<>();

	private enum ImageDetailRow {
		NAME, URI, PIXEL_TYPE, MAGNIFICATION, WIDTH, HEIGHT, DIMENSIONS,
		PIXEL_WIDTH, PIXEL_HEIGHT, Z_SPACING, UNCOMPRESSED_SIZE, SERVER_TYPE, PYRAMID,
		METADATA_CHANGED, IMAGE_TYPE,
		STAIN_1, STAIN_2, STAIN_3, BACKGROUND;
	};

	private static List<ImageDetailRow> brightfieldRows;
	private static List<ImageDetailRow> otherRows;
	
	static {
		brightfieldRows = Arrays.asList(ImageDetailRow.values());
		otherRows = new ArrayList<>(brightfieldRows);
		otherRows.remove(ImageDetailRow.STAIN_1);
		otherRows.remove(ImageDetailRow.STAIN_2);
		otherRows.remove(ImageDetailRow.STAIN_3);
		otherRows.remove(ImageDetailRow.BACKGROUND);
	}

	/**
	 * Constructor.
	 * @param imageDataProperty 
	 */
	public ImageDetailsPane(final ObservableValue<ImageData<BufferedImage>> imageDataProperty) {
		imageDataProperty.addListener(this);

		// Create the table
		table.setPlaceholder(GuiTools.createPlaceholderText("No image selected"));
		table.setMinHeight(200);
		table.setPrefHeight(250);
		table.setMaxHeight(Double.MAX_VALUE);
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
		TableColumn<ImageDetailRow, String> columnName = new TableColumn<>("Name");
		columnName.setCellValueFactory(v -> new ReadOnlyStringWrapper(getName(v.getValue())));
		columnName.setEditable(false);
		columnName.setPrefWidth(150);
		TableColumn<ImageDetailRow, Object> columnValue = new TableColumn<>("Value");
		columnValue.setCellValueFactory(v -> new ReadOnlyObjectWrapper<>(getValue(v.getValue())));
		columnValue.setEditable(false);
		columnValue.setPrefWidth(200);
		columnValue.setCellFactory(c -> new ImageDetailTableCell(imageDataProperty));
		table.getColumns().add(columnName);
		table.getColumns().add(columnValue);

		setImageData(imageDataProperty.getValue());

		listAssociatedImages.setOnMouseClicked(this::handleAssociatedImagesMouseClick);

		PathPrefs.maskImageNamesProperty().addListener((v, o, n) -> table.refresh());

		MasterDetailPane mdPane = new MasterDetailPane(Side.BOTTOM);
		mdPane.setMasterNode(new StackPane(table));
		var titlePaneAssociated = new TitledPane("Associated images", listAssociatedImages);
		titlePaneAssociated.setCollapsible(false);
		listAssociatedImages.setTooltip(new Tooltip(
				"Extra images associated with the current image, e.g. a label or thumbnail"));
		mdPane.setDetailNode(titlePaneAssociated);
		mdPane.showDetailNodeProperty().bind(
				Bindings.createBooleanBinding(() -> !listAssociatedImages.getItems().isEmpty(),
						listAssociatedImages.getItems()));
		pane.getChildren().add(mdPane);
	}
	
	
	
	private void handleAssociatedImagesMouseClick(MouseEvent event) {
		if (event.getClickCount() < 2 || listAssociatedImages.getSelectionModel().getSelectedItem() == null)
			return;
		String name = listAssociatedImages.getSelectionModel().getSelectedItem();
		var simpleViewer = associatedImageViewers.get(name);
		if (simpleViewer == null) {
			simpleViewer = new SimpleImageViewer();
			var img = imageData.getServer().getAssociatedImage(name);
			simpleViewer.updateImage(name, img);
			var stage = simpleViewer.getStage();
			var owner = FXUtils.getWindow(getPane());
			stage.initOwner(owner);
			stage.setOnCloseRequest(e -> {
				associatedImageViewers.remove(name);
				stage.close();
				e.consume();
			});
			// Show with constrained size (in case we have a large image)
			GuiTools.showWithScreenSizeConstraints(stage, 0.8);
			associatedImageViewers.put(name, simpleViewer);
		} else {
			simpleViewer.getStage().show();
			simpleViewer.getStage().toFront();
		}
	}


	private static boolean hasOriginalMetadata(ImageServer<BufferedImage> server) {
		var metadata = server.getMetadata();
		var originalMetadata = server.getOriginalMetadata();
		return Objects.equals(metadata, originalMetadata);
	}


	private static boolean promptToResetServerMetadata(ImageData<BufferedImage> imageData) {
		var server = imageData.getServer();
		if (hasOriginalMetadata(server)) {
			logger.info("ImageServer metadata is unchanged!");
			return false;
		}
		var originalMetadata = server.getOriginalMetadata();

		if (Dialogs.showConfirmDialog("Reset metadata", "Reset to original metadata?")) {
			imageData.updateServerMetadata(originalMetadata);
			return true;
		}
		return false;
	}


	private static boolean promptToSetMagnification(ImageData<BufferedImage> imageData) {
		var server = imageData.getServer();
		Double mag = server.getMetadata().getMagnification();
		Double mag2 = Dialogs.showInputDialog("Set magnification", "Set magnification for full resolution image", mag);
		if (mag2 == null || Double.isInfinite(mag) || Objects.equals(mag, mag2))
			return false;
		var metadata2 = new ImageServerMetadata.Builder(server.getMetadata())
				.magnification(mag2)
				.build();
		imageData.updateServerMetadata(metadata2);
		return true;
	}

	private static boolean promptToSetPixelSize(ImageData<BufferedImage> imageData, boolean requestZSpacing) {
		var server = imageData.getServer();
		var hierarchy = imageData.getHierarchy();
		var selected = hierarchy.getSelectionModel().getSelectedObject();
		var roi = selected == null ? null : selected.getROI();

		PixelCalibration cal = server.getPixelCalibration();
		double pixelWidthMicrons = cal.getPixelWidthMicrons();
		double pixelHeightMicrons = cal.getPixelHeightMicrons();
		double zSpacingMicrons = cal.getZSpacingMicrons();

		// Use line or area ROI if possible
		if (!requestZSpacing && roi != null && !roi.isEmpty() && (roi.isArea() || roi.isLine())) {
			boolean setPixelHeight = true;
			boolean setPixelWidth = true;	
			String message;
			String units = GeneralTools.micrometerSymbol();

			double pixelWidth = cal.getPixelWidthMicrons();
			double pixelHeight = cal.getPixelHeightMicrons();
			if (!Double.isFinite(pixelWidth))
				pixelWidth = 1;
			if (!Double.isFinite(pixelHeight))
				pixelHeight = 1;

			Double defaultValue = null;
			if (roi.isLine()) {
				setPixelHeight = roi.getBoundsHeight() != 0;
				setPixelWidth = roi.getBoundsWidth() != 0;
				message = "Enter selected line length";
				defaultValue = roi.getScaledLength(pixelWidth, pixelHeight);
			} else {
				message = "Enter selected ROI area";
				units = units + "^2";
				defaultValue = roi.getScaledArea(pixelWidth, pixelHeight);
			}

			if (Double.isNaN(defaultValue))
				defaultValue = 1.0;
			var params = new ParameterList()
					.addDoubleParameter("inputValue", message, defaultValue, units, "Enter calibrated value in " + units + " for the selected ROI to calculate the pixel size")
					.addBooleanParameter("squarePixels", "Assume square pixels", true, "Set the pixel width to match the pixel height");
			params.setHiddenParameters(setPixelHeight && setPixelWidth, "squarePixels");
			if (!GuiTools.showParameterDialog("Set pixel size", params))
				return false;
			Double result = params.getDoubleParameterValue("inputValue");
			setPixelHeight = setPixelHeight || params.getBooleanParameterValue("squarePixels");
			setPixelWidth = setPixelWidth || params.getBooleanParameterValue("squarePixels");

			double sizeMicrons;
			if (roi.isLine())
				sizeMicrons = result.doubleValue() / roi.getLength();
			else
				sizeMicrons = Math.sqrt(result.doubleValue() / roi.getArea());

			if (setPixelHeight)
				pixelHeightMicrons = sizeMicrons;
			if (setPixelWidth)
				pixelWidthMicrons = sizeMicrons;
		} else {
			// Prompt for all required values
			ParameterList params = new ParameterList()
					.addDoubleParameter("pixelWidth", "Pixel width", pixelWidthMicrons, GeneralTools.micrometerSymbol(), "Enter the pixel width")
					.addDoubleParameter("pixelHeight", "Pixel height", pixelHeightMicrons, GeneralTools.micrometerSymbol(), "Entry the pixel height")
					.addDoubleParameter("zSpacing", "Z-spacing", zSpacingMicrons, GeneralTools.micrometerSymbol(), "Enter the spacing between slices of a z-stack");
			params.setHiddenParameters(server.nZSlices() == 1, "zSpacing");
			if (!GuiTools.showParameterDialog("Set pixel size", params))
				return false;
			if (server.nZSlices() != 1) {
				zSpacingMicrons = params.getDoubleParameterValue("zSpacing");
			}
			pixelWidthMicrons = params.getDoubleParameterValue("pixelWidth");
			pixelHeightMicrons = params.getDoubleParameterValue("pixelHeight");
		}
		if ((pixelWidthMicrons <= 0 || pixelHeightMicrons <= 0) || (server.nZSlices() > 1 && zSpacingMicrons <= 0)) {
			if (!Dialogs.showConfirmDialog("Set pixel size", "You entered values <= 0, do you really want to remove this pixel calibration information?")) {
				return false;
			}
			zSpacingMicrons = server.nZSlices() > 1 && zSpacingMicrons > 0 ? zSpacingMicrons : Double.NaN;
			if (pixelWidthMicrons <= 0 || pixelHeightMicrons <= 0) {
				pixelWidthMicrons = Double.NaN;
				pixelHeightMicrons = Double.NaN;
			}
		}
		if (QP.setPixelSizeMicrons(imageData, pixelWidthMicrons, pixelHeightMicrons, zSpacingMicrons)) {
			// Log for scripts
			WorkflowStep step;
			if (server.nZSlices() == 1) {
				var map = Map.of("pixelWidthMicrons", pixelWidthMicrons,
						"pixelHeightMicrons", pixelHeightMicrons);
				String script = String.format("setPixelSizeMicrons(%f, %f)", pixelWidthMicrons, pixelHeightMicrons);
				step = new DefaultScriptableWorkflowStep("Set pixel size " + GeneralTools.micrometerSymbol(), map, script);
			} else {
				var map = Map.of("pixelWidthMicrons", pixelWidthMicrons,
						"pixelHeightMicrons", pixelHeightMicrons,
						"zSpacingMicrons", zSpacingMicrons);
				String script = String.format("setPixelSizeMicrons(%f, %f, %f)", pixelWidthMicrons, pixelHeightMicrons, zSpacingMicrons);
				step = new DefaultScriptableWorkflowStep("Set pixel size " + GeneralTools.micrometerSymbol(), map, script);
			}
			imageData.getHistoryWorkflow().addStep(step);
			return true;
		} else
			return false;
	}


	/**
	 * Prompt the user to set the {@link ImageType} for the image.
	 * @param imageData the image data for which the type should be set
	 * @param defaultType the default type (selected when the dialog is shown)
	 * @return true if the type was changed, false otherwise
	 */
	public static boolean promptToSetImageType(ImageData<BufferedImage> imageData, ImageType defaultType) {
		double size = 32;
		var group = new ToggleGroup();
		boolean isRGB = imageData.getServerMetadata().getChannels().size() == 3; // v0.6.0 supports non-8-bit color deconvolution
		if (defaultType == null)
			defaultType = ImageType.UNSET;
		var buttonMap = new LinkedHashMap<ImageType, ToggleButton>();

		// TODO: Create a nicer icon for unspecified type
		var iconUnspecified = (Group)createImageTypeCell(Color.GRAY, null, null, size);

		if (isRGB) {
			buttonMap.put(
					ImageType.BRIGHTFIELD_H_E,
					createImageTypeButton(ImageType.BRIGHTFIELD_H_E, "Brightfield\nH&E",
							createImageTypeCell(Color.WHITE, Color.PINK, Color.DARKBLUE, size),
							"Brightfield image with hematoylin & eosin stains\n(8-bit RGB only)", isRGB)
					);

			buttonMap.put(
					ImageType.BRIGHTFIELD_H_DAB,
					createImageTypeButton(ImageType.BRIGHTFIELD_H_DAB, "Brightfield\nH-DAB",
							createImageTypeCell(Color.WHITE, Color.rgb(200, 200, 220), Color.rgb(120, 50, 20), size),
							"Brightfield image with hematoylin & DAB stains\n(8-bit RGB only)", isRGB)
					);

			buttonMap.put(
					ImageType.BRIGHTFIELD_OTHER,
					createImageTypeButton(ImageType.BRIGHTFIELD_OTHER, "Brightfield\nOther",
							createImageTypeCell(Color.WHITE, Color.ORANGE, Color.FIREBRICK, size),
							"Brightfield image with other chromogenic stains\n(8-bit RGB only)", isRGB)
					);
		}

		buttonMap.put(
				ImageType.FLUORESCENCE,
				createImageTypeButton(ImageType.FLUORESCENCE, "Fluorescence",
						createImageTypeCell(Color.BLACK, Color.LIGHTGREEN, Color.BLUE, size),
						"Fluorescence or fluorescence-like image with a dark background\n" +
								"Also suitable for imaging mass cytometry", true)
				);

		buttonMap.put(
				ImageType.OTHER,
				createImageTypeButton(ImageType.OTHER, "Other",
						createImageTypeCell(Color.BLACK, Color.WHITE, Color.GRAY, size),
						"Any other image type", true)
				);

		buttonMap.put(
				ImageType.UNSET,
				createImageTypeButton(ImageType.UNSET, "Unspecified",
						iconUnspecified,
						"Do not set the image type (not recommended for analysis)", true)
				);

		var buttons = buttonMap.values().toArray(ToggleButton[]::new);
		for (var btn: buttons) {
			if (btn.isDisabled()) {
				btn.getTooltip().setText("Image type is not supported because image is not RGB");
			}
		}
		var buttonList = Arrays.asList(buttons);

		group.getToggles().setAll(buttons);
		group.selectedToggleProperty().addListener((v, o, n) -> {
			// Ensure that we can't deselect all buttons
			if (n == null)
				o.setSelected(true);
		});

		GridPaneUtils.setMaxWidth(Double.MAX_VALUE, buttons);
		GridPaneUtils.setMaxHeight(Double.MAX_VALUE, buttons);
		var selectedButton = buttonMap.get(defaultType);
		group.selectToggle(selectedButton);

		var grid = new GridPane();
		int nHorizontal = 3;
		int nVertical = (int)Math.ceil(buttons.length / (double)nHorizontal);
		grid.getColumnConstraints().setAll(IntStream.range(0, nHorizontal).mapToObj(i -> {
			var c = new ColumnConstraints();
			c.setPercentWidth(100.0/nHorizontal);
			return c;
		}).toList());

		grid.getRowConstraints().setAll(IntStream.range(0, nVertical).mapToObj(i -> {
			var c = new RowConstraints();
			c.setPercentHeight(100.0/nVertical);
			return c;
		}).toList());

		grid.setVgap(5);
		//		grid.setHgap(5);
		grid.setMaxWidth(Double.MAX_VALUE);
		for (int i = 0; i < buttons.length; i++) {
			grid.add(buttons[i], i % nHorizontal, i / nHorizontal);
		}
		//		grid.getChildren().setAll(buttons);

		var content = new BorderPane(grid);
		var comboOptions = new ComboBox<ImageTypeSetting>();
		comboOptions.getItems().setAll(ImageTypeSetting.values());

		var prompts = Map.of(
				ImageTypeSetting.AUTO_ESTIMATE, "Always auto-estimate type (don't prompt)",
				ImageTypeSetting.PROMPT, "Always prompt me to set type",
				ImageTypeSetting.NONE, "Don't set the image type"
				);
		comboOptions.setButtonCell(FXUtils.createCustomListCell(p -> prompts.get(p)));
		comboOptions.setCellFactory(c -> FXUtils.createCustomListCell(p -> prompts.get(p)));
		comboOptions.setTooltip(
				new Tooltip("Choose whether you want to see these prompts " +
						"when opening an image for the first time"));
		comboOptions.setMaxWidth(Double.MAX_VALUE);
		//		comboOptions.prefWidthProperty().bind(grid.widthProperty().subtract(100));
		comboOptions.getSelectionModel().select(PathPrefs.imageTypeSettingProperty().get());

		if (nVertical > 1)
			BorderPane.setMargin(comboOptions, new Insets(5, 0, 0, 0));
		else
			BorderPane.setMargin(comboOptions, new Insets(10, 0, 0, 0));
		content.setBottom(comboOptions);

		var labelDetails = new Label("The image type is used for stain separation "
				+ "by some commands, e.g. 'Cell detection'.\n"
				+ "Brightfield types are only available for 8-bit RGB images.");
		//				+ "For 'Brightfield' images you can set the color stain vectors.");
		labelDetails.setWrapText(true);
		labelDetails.prefWidthProperty().bind(grid.widthProperty().subtract(10));
		labelDetails.setMaxHeight(Double.MAX_VALUE);
		labelDetails.setPrefHeight(Label.USE_COMPUTED_SIZE);
		labelDetails.setPrefHeight(100);
		labelDetails.setAlignment(Pos.CENTER);
		labelDetails.setTextAlignment(TextAlignment.CENTER);

		var dialog = Dialogs.builder()
				.title("Set image type")
				.headerText("What type of image is this?")
				.content(content)
				.buttons(ButtonType.APPLY, ButtonType.CANCEL)
				.expandableContent(labelDetails)
				.build();

		// Try to make it easier to dismiss the dialog in a variety of ways
		var btnApply = dialog.getDialogPane().lookupButton(ButtonType.APPLY);
		Platform.runLater(() -> selectedButton.requestFocus());
		for (var btn : buttons) {
			btn.setOnMouseClicked(e -> {
				if (!btn.isDisabled() && e.getClickCount() == 2) {
					btnApply.fireEvent(new ActionEvent());
					e.consume();					
				}
			});
		}
		var enterPressed = new KeyCodeCombination(KeyCode.ENTER);
		var spacePressed = new KeyCodeCombination(KeyCode.SPACE);
		dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
			if (enterPressed.match(e) || spacePressed.match(e)) {
				btnApply.fireEvent(new ActionEvent());
				e.consume();
			} else if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
				var selected = (ToggleButton)group.getSelectedToggle();
				var ind = buttonList.indexOf(selected);
				var newSelected = selected;
				if (e.getCode() == KeyCode.UP && ind >= nHorizontal) {
					newSelected = buttonList.get(ind - nHorizontal);
				}
				if (e.getCode() == KeyCode.LEFT && ind > 0) {
					newSelected = buttonList.get(ind - 1);
				}
				if (e.getCode() == KeyCode.RIGHT && ind < buttonList.size()-1) {
					newSelected = buttonList.get(ind + 1);
				}
				if (e.getCode() == KeyCode.DOWN && ind < buttonList.size() - nHorizontal) {
					newSelected = buttonList.get(ind + nHorizontal);
				}
				newSelected.requestFocus();
				group.selectToggle(newSelected);
				e.consume();
			}
		});

		var response = dialog.showAndWait();
		if (response.orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
			PathPrefs.imageTypeSettingProperty().set(comboOptions.getSelectionModel().getSelectedItem());
			var selectedType = (ImageType)group.getSelectedToggle().getUserData();
			if (selectedType != imageData.getImageType()) {
				imageData.setImageType(selectedType);
				return true;
			}
		}
		return false;
	}

	/**
	 * Create a standardized toggle button for setting the image type
	 * @param name
	 * @param node
	 * @param tooltip
	 * @return
	 */
	private static ToggleButton createImageTypeButton(ImageType type, String name, Node node, String tooltip, boolean isEnabled) {
		var btn = new ToggleButton(name, node);
		if (tooltip != null) {
			btn.setTooltip(new Tooltip(tooltip));
		}
		btn.setTextAlignment(TextAlignment.CENTER);
		btn.setAlignment(Pos.TOP_CENTER);
		btn.setContentDisplay(ContentDisplay.BOTTOM);
		btn.setOpacity(0.6);
		btn.selectedProperty().addListener((v, o, n) -> {
			if (n)
				btn.setOpacity(1.0);
			else
				btn.setOpacity(0.6);
		});
		btn.setUserData(type);
		if (!isEnabled)
			btn.setDisable(true);
		return btn;
	}

	/**
	 * Create a small icon of a cell, for use with image type buttons.
	 * @param bgColor
	 * @param cytoColor
	 * @param nucleusColor
	 * @param size
	 * @return
	 */
	private static Node createImageTypeCell(Color bgColor, Color cytoColor, Color nucleusColor, double size) {
		var group = new Group();
		if (bgColor != null) {
			var rect = new Rectangle(0, 0, size, size);
			rect.setFill(bgColor);
			rect.setEffect(new DropShadow(5.0, Color.BLACK));
			group.getChildren().add(rect);
		}
		if (cytoColor != null) {
			var cyto = new Ellipse(size/2.0, size/2.0, size/3.0, size/3.0);
			cyto.setFill(cytoColor);
			cyto.setEffect(new DropShadow(2.5, Color.BLACK));
			group.getChildren().add(cyto);
		}
		if (nucleusColor != null) {
			var nucleus = new Ellipse(size/2.4, size/2.4, size/5.0, size/5.0);
			nucleus.setFill(nucleusColor);
			nucleus.setEffect(new DropShadow(2.5, Color.BLACK));
			group.getChildren().add(nucleus);
		}
		group.setOpacity(0.7);
		return group;
	}




	/**
	 * Get the {@link Pane} component for addition to a scene.
	 * @return
	 */
	public Pane getPane() {
		return pane;
	}

	private void setImageData(ImageData<BufferedImage> imageData) {
		if (this.imageData != null)
			this.imageData.removePropertyChangeListener(this);

		this.imageData = imageData;
		ImageServer<BufferedImage> server = null;
		if (imageData != null) {
			imageData.addPropertyChangeListener(this);
			server = imageData.getServer();
		}

		table.getItems().setAll(getRows());
		table.refresh();

		if (listAssociatedImages != null) {
			if (server == null)
				listAssociatedImages.getItems().clear();
			else
				listAssociatedImages.getItems().setAll(server.getAssociatedImageList());
		}

		// Check if we're showing associated images
		for (var entry : associatedImageViewers.entrySet()) {
			var name = entry.getKey();
			var simpleViewer = entry.getValue();
			logger.trace("Updating associated image viewer for {}", name);
			if (server == null || !server.getAssociatedImageList().contains(name))
				simpleViewer.updateImage(name, (BufferedImage)null); // Hack to retain the title, without an image
			else
				simpleViewer.updateImage(name, server.getAssociatedImage(name));
		}
	}


	@Override
	public void propertyChange(PropertyChangeEvent evt) {
		setImageData(imageData);
	}


	@Override
	public void changed(ObservableValue<? extends ImageData<BufferedImage>> source, ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {
		setImageData(imageDataNew);
	}


	private List<ImageDetailRow> getRows() {
		if (imageData == null || imageData.getServer() == null)
			return Collections.emptyList();
		var list = new ArrayList<ImageDetailRow>();
		if (imageData.isBrightfield())
			list.addAll(brightfieldRows);
		else
			list.addAll(otherRows);
		if (imageData.getServer().nZSlices() == 1)
			list.remove(ImageDetailRow.Z_SPACING);
		return list;
	}

	private String getName(ImageDetailRow row) {
		switch (row) {
		case NAME:
			return "Name";
		case URI:
			if (imageData != null && imageData.getServer().getURIs().size() == 1)
				return "URI";
			return "URIs";
		case IMAGE_TYPE:
			return "Image type";
		case METADATA_CHANGED:
			return "Metadata changed";
		case PIXEL_TYPE:
			return "Pixel type";
		case MAGNIFICATION:
			return "Magnification";
		case WIDTH:
			return "Width";
		case HEIGHT:
			return "Height";
		case DIMENSIONS:
			return "Dimensions (CZT)";
		case PIXEL_WIDTH:
			return "Pixel width";
		case PIXEL_HEIGHT:
			return "Pixel height";
		case Z_SPACING:
			return "Z-spacing";
		case UNCOMPRESSED_SIZE:
			return "Uncompressed size";
		case SERVER_TYPE:
			return "Server type";
		case PYRAMID:
			return "Pyramid";
		case STAIN_1:
			return "Stain 1";
		case STAIN_2:
			return "Stain 2";
		case STAIN_3:
			return "Stain 3";
		case BACKGROUND:
			return "Background";
		default:
			return null;
		}
	}

	private static String decodeURI(URI uri) {
		try {
			return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return uri.toString();
		}
	}

	private Object getValue(ImageDetailRow rowType) {
		if (imageData == null)
			return null;
		ImageServer<BufferedImage> server = imageData.getServer();
		PixelCalibration cal = server.getPixelCalibration();
		switch (rowType) {
		case NAME:
			var project = QuPathGUI.getInstance().getProject();
			var entry = project == null ? null : project.getEntry(imageData);
			if (entry == null)
				return ServerTools.getDisplayableImageName(server);
			else
				return entry.getImageName();
		case URI:
			Collection<URI> uris = server.getURIs();
			if (uris.isEmpty())
				return "Not available";
			if (uris.size() == 1)
				return decodeURI(uris.iterator().next());
			return "[" + String.join(", ", uris.stream().map(ImageDetailsPane::decodeURI).toList()) + "]";
		case IMAGE_TYPE:
			return imageData.getImageType();
		case METADATA_CHANGED:
			return hasOriginalMetadata(imageData.getServer()) ? "No" : "Yes";
		case PIXEL_TYPE:
			String type = server.getPixelType().toString().toLowerCase();
			if (server.isRGB())
				type += " (rgb)";
			return type;
		case MAGNIFICATION:
			double mag = server.getMetadata().getMagnification();
			if (Double.isNaN(mag))
				return "Unknown";
			return mag;
		case WIDTH:
			if (cal.hasPixelSizeMicrons())
				return String.format("%s px (%.2f %s)", server.getWidth(), server.getWidth() * cal.getPixelWidthMicrons(), GeneralTools.micrometerSymbol());
			else
				return String.format("%s px", server.getWidth());
		case HEIGHT:
			if (cal.hasPixelSizeMicrons())
				return String.format("%s px (%.2f %s)", server.getHeight(), server.getHeight() * cal.getPixelHeightMicrons(), GeneralTools.micrometerSymbol());
			else
				return String.format("%s px", server.getHeight());
		case DIMENSIONS:
			return String.format("%d x %d x %d", server.nChannels(), server.nZSlices(), server.nTimepoints());
		case PIXEL_WIDTH:
			if (cal.hasPixelSizeMicrons())
				return String.format("%.4f %s", cal.getPixelWidthMicrons(), GeneralTools.micrometerSymbol());
			else
				return "Unknown";
		case PIXEL_HEIGHT:
			if (cal.hasPixelSizeMicrons())
				return String.format("%.4f %s", cal.getPixelHeightMicrons(), GeneralTools.micrometerSymbol());
			else
				return "Unknown";
		case Z_SPACING:
			if (cal.hasZSpacingMicrons())
				return String.format("%.4f %s", cal.getZSpacingMicrons(), GeneralTools.micrometerSymbol());
			else
				return "Unknown";
		case UNCOMPRESSED_SIZE:
			double size =
			server.getWidth()/1024.0 * server.getHeight()/1024.0 * 
			server.getPixelType().getBytesPerPixel() * server.nChannels() *
			server.nZSlices() * server.nTimepoints();
			String units = " MB";
			if (size > 1000) {
				size /= 1024.0;
				units = " GB";
			}
			return GeneralTools.formatNumber(size, 1) + units;
		case SERVER_TYPE:
			return server.getServerType();
		case PYRAMID:
			if (server.nResolutions() == 1)
				return "No";
			return GeneralTools.arrayToString(Locale.getDefault(Locale.Category.FORMAT), server.getPreferredDownsamples(), 1);
		case STAIN_1:
			return imageData.getColorDeconvolutionStains().getStain(1);
		case STAIN_2:
			return imageData.getColorDeconvolutionStains().getStain(2);
		case STAIN_3:
			return imageData.getColorDeconvolutionStains().getStain(3);
		case BACKGROUND:
			ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
			double[] whitespace = new double[]{stains.getMaxRed(), stains.getMaxGreen(), stains.getMaxBlue()};
			return whitespace;
		default:
			return null;
		}

	}
	
	
	private static class ImageDetailTableCell extends TableCell<ImageDetailRow, Object> {
		
		private ObservableValue<ImageData<BufferedImage>> imageDataProperty;

		ImageDetailTableCell(ObservableValue<ImageData<BufferedImage>> imageDataProperty) {
			this.imageDataProperty = imageDataProperty;
			setOnMouseClicked(this::handleMouseClick);
		}


		@Override
		protected void updateItem(Object item, boolean empty) {
			super.updateItem(item, empty);
			if (empty) {
				setText(null);
				setGraphic(null);
				return;
			}
			//			             ComboBoxTableCell<TableEntry, Object>
			String style = null;
			String text = item == null ? "" : item.toString();
			String tooltipText = text;
			if (item instanceof double[]) {
				text = GeneralTools.arrayToString(Locale.getDefault(Category.FORMAT), (double[])item, 2);
				tooltipText = "Double-click to set background values for color deconvolution (either type values or use a small rectangle ROI in the image)";
			} else if (item instanceof StainVector) {
				StainVector stain = (StainVector)item;
				Integer color = stain.getColor();
				style = String.format("-fx-text-fill: rgb(%d, %d, %d);", ColorTools.red(color), ColorTools.green(color), ColorTools.blue(color));
				tooltipText = "Double-click to set stain color (either type values or use a small rectangle ROI in the image)";
			} else {
				var type = getTableRow().getItem();
				if (type != null) {
					if (type.equals(ImageDetailRow.PIXEL_WIDTH) || type.equals(ImageDetailRow.PIXEL_HEIGHT) || type.equals(ImageDetailRow.Z_SPACING)) {
						if ("Unknown".equals(item))
							style = "-fx-text-fill: red;";
						tooltipText = "Double-click to set pixel calibration (can use a selected line or area ROI in the image)";
					} else if (type.equals(ImageDetailRow.METADATA_CHANGED))
						tooltipText = "Double-click to reset original metadata";
					else if (type.equals(ImageDetailRow.UNCOMPRESSED_SIZE))
						tooltipText = "Approximate memory required to store all pixels in the image uncompressed";
				}
			}
			setStyle(style);
			setText(text);
			setTooltip(new Tooltip(tooltipText));
		}

		private void handleMouseClick(MouseEvent event) {
			var imageData = imageDataProperty.getValue();
			if (event.getClickCount() < 2 || imageData == null)
				return;
			TableCell<ImageDetailRow, Object> c = (TableCell<ImageDetailRow, Object>)event.getSource();
			Object value = c.getItem();
			if (value instanceof StainVector || value instanceof double[])
				editStainVector(imageData, value);
			else if (value instanceof ImageType) {
				promptToSetImageType(imageData, imageData.getImageType());
			} else {
				// TODO: Support z-spacing
				var type = c.getTableRow().getItem();
				boolean metadataChanged = false;
				if (type == ImageDetailRow.PIXEL_WIDTH ||
						type == ImageDetailRow.PIXEL_HEIGHT ||
						type == ImageDetailRow.Z_SPACING) {
					metadataChanged = promptToSetPixelSize(imageData, type == ImageDetailRow.Z_SPACING);
				} else if (type == ImageDetailRow.MAGNIFICATION) {
					metadataChanged = promptToSetMagnification(imageData);
				} else if (type == ImageDetailRow.METADATA_CHANGED) {
					if (!hasOriginalMetadata(imageData.getServer())) {
						metadataChanged = promptToResetServerMetadata(imageData);
					}
				}
				if (metadataChanged) {
					c.getTableView().refresh();
					imageData.getHierarchy().fireHierarchyChangedEvent(this);
				}
			}
		}
		
		
		private static void editStainVector(ImageData<BufferedImage> imageData, Object value) {
			if (imageData == null || !(value instanceof StainVector || value instanceof double[]))
				return;
			
			ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
			int num = -1; // Default to background values
			String name = null;
			String message = null;
			if (value instanceof StainVector) {

				StainVector stainVector = (StainVector)value;

				if (stainVector.isResidual() && imageData.getImageType() != ImageType.BRIGHTFIELD_OTHER) {
					logger.warn("Cannot set residual stain vector - this is computed from the known vectors");
					return;
				}
				num = stains.getStainNumber(stainVector);
				if (num <= 0) {
                    logger.error("Could not identify stain vector {} inside {}", stainVector, stains);
					return;
				}
				name = stainVector.getName();
				message = "Set stain vector from ROI?";
			} else
				message = "Set color deconvolution background values from ROI?";

			ROI roi = imageData.getHierarchy().getSelectionModel().getSelectedROI();
			boolean wasChanged = false;
			String warningMessage = null;
			boolean editableName = imageData.getImageType() == ImageType.BRIGHTFIELD_OTHER;
			if (roi != null) {
				if ((roi instanceof RectangleROI) && 
						!roi.isEmpty() &&
						roi.getArea() < 500*500) {
					if (Dialogs.showYesNoDialog("Color deconvolution stains", message)) {
						ImageServer<BufferedImage> server = imageData.getServer();
						BufferedImage img = null;
						try {
							img = server.readRegion(RegionRequest.createInstance(server.getPath(), 1, roi));
						} catch (IOException e) {
							Dialogs.showErrorMessage("Set stain vector", "Unable to read image region");
							logger.error("Unable to read region", e);
							return;
						}
						if (num >= 0) {
							StainVector vectorValue = ColorDeconvolutionHelper.generateMedianStainVectorFromPixels(name, img, stains.getMaxRed(), stains.getMaxGreen(), stains.getMaxBlue());
							if (!Double.isFinite(vectorValue.getRed() + vectorValue.getGreen() + vectorValue.getBlue())) {
								Dialogs.showErrorMessage("Set stain vector",
										"Cannot set stains for the current ROI!\n"
												+ "It might be too close to the background color.");
								return;
							}
							value = vectorValue;
						} else {
							// Update the background
							if (BufferedImageTools.is8bitColorType(img.getType())) {
								int rgb = ColorDeconvolutionHelper.getMedianRGB(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
								value = new double[]{ColorTools.red(rgb), ColorTools.green(rgb), ColorTools.blue(rgb)};
							} else {
								double r = ColorDeconvolutionHelper.getMedian(ColorDeconvolutionHelper.getPixels(img.getRaster(), 0));
								double g = ColorDeconvolutionHelper.getMedian(ColorDeconvolutionHelper.getPixels(img.getRaster(), 1));
								double b = ColorDeconvolutionHelper.getMedian(ColorDeconvolutionHelper.getPixels(img.getRaster(), 2));
								value = new double[]{r, g, b};
							}
						}
						wasChanged = true;
					}
				} else {
					warningMessage = "Note: To set stain values from an image region, draw a small, rectangular ROI first";
				}
			}

			// Prompt to set the name / verify stains
			ParameterList params = new ParameterList();
			String title;
			String nameBefore = null;
			String valuesBefore = null;
			String collectiveNameBefore = stains.getName();
			String suggestedName;
			if (collectiveNameBefore.endsWith("default"))
				suggestedName = collectiveNameBefore.substring(0, collectiveNameBefore.lastIndexOf("default")) + "modified";
			else
				suggestedName = collectiveNameBefore;
			params.addStringParameter("collectiveName", "Collective name", suggestedName, "Enter collective name for all 3 stains (e.g. H-DAB Scanner A, H&E Scanner B)");
			if (value instanceof StainVector) {
				nameBefore = ((StainVector)value).getName();
				valuesBefore = ((StainVector)value).arrayAsString(Locale.getDefault(Category.FORMAT));
				params.addStringParameter("name", "Name", nameBefore, "Enter stain name")
				.addStringParameter("values", "Values", valuesBefore, "Enter 3 values (red, green, blue) defining color deconvolution stain vector, separated by spaces");
				title = "Set stain vector";
			} else {
				nameBefore = "Background";
				valuesBefore = GeneralTools.arrayToString(Locale.getDefault(Category.FORMAT), (double[])value, 2);
				params.addStringParameter("name", "Stain name", nameBefore);
				params.addStringParameter("values", "Stain values", valuesBefore, "Enter 3 values (red, green, blue) defining background, separated by spaces");
				params.setHiddenParameters(true, "name");
				title = "Set background";
			}

			if (warningMessage != null)
				params.addEmptyParameter(warningMessage);

			// Disable editing the name if it should be fixed
			ParameterPanelFX parameterPanel = new ParameterPanelFX(params);
			parameterPanel.setParameterEnabled("name", editableName);;
			if (!Dialogs.showConfirmDialog(title, parameterPanel.getPane()))
				return;

			// Check if anything changed
			String collectiveName = params.getStringParameterValue("collectiveName");
			String nameAfter = params.getStringParameterValue("name");
			String valuesAfter = params.getStringParameterValue("values");
			if (collectiveName.equals(collectiveNameBefore) && nameAfter.equals(nameBefore) && valuesAfter.equals(valuesBefore) && !wasChanged)
				return;

			if (Set.of("Red", "Green", "Blue").contains(nameAfter)) {
				Dialogs.showErrorMessage("Set stain vector", "Cannot set stain name to 'Red', 'Green', or 'Blue' - please choose a different name");
				return;
			}

			double[] valuesParsed = ColorDeconvolutionStains.parseStainValues(Locale.getDefault(Category.FORMAT), valuesAfter);
			if (valuesParsed == null) {
				logger.error("Input for setting color deconvolution information invalid! Cannot parse 3 numbers from {}", valuesAfter);
				return;
			}

			if (num >= 0) {
				try {
					stains = stains.changeStain(StainVector.createStainVector(nameAfter, valuesParsed[0], valuesParsed[1], valuesParsed[2]), num);					
				} catch (Exception e) {
					logger.error("Error setting stain vectors", e);
					Dialogs.showErrorMessage("Set stain vectors", "Requested stain vectors are not valid!\nAre two stains equal?");
				}
			} else {
				// Update the background
				stains = stains.changeMaxValues(valuesParsed[0], valuesParsed[1], valuesParsed[2]);
			}

			// Set the collective name
			stains = stains.changeName(collectiveName);
			imageData.setColorDeconvolutionStains(stains);
		}

	};
	

}