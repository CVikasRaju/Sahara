import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import '../../core/theme.dart';

/// ── Web Mercator maths ────────────────────────────────────────
///
/// Kept as pure static functions so the projection can be unit-tested without
/// a widget tree — map maths that is off by a tile is exactly the kind of bug
/// that only shows up on a real device, in the field, with no network.
class MercatorProjection {
  MercatorProjection._();

  /// Side of a single tile, in pixels (the universal slippy-map constant).
  static const double tileSize = 256.0;

  /// Latitude cutoff of the Mercator projection (`atan(sinh(pi))`).
  static const double maxLatitude = 85.05112878;

  /// Width/height of the whole world in pixels at [zoom].
  static double worldSize(int zoom) => tileSize * (1 << zoom);

  /// Pixel X for [lon] at [zoom].
  static double xForLon(double lon, int zoom) =>
      (lon + 180.0) / 360.0 * worldSize(zoom);

  /// Pixel Y for [lat] at [zoom].
  ///
  /// Clamped to the world rectangle: at the projection limit the formula lands
  /// a hair outside (about -2.5e-8 px), and an off-world pixel produces a
  /// negative tile index that then has to be special-cased everywhere.
  static double yForLat(double lat, int zoom) {
    final clamped = lat.clamp(-maxLatitude, maxLatitude);
    final sinLat = math.sin(clamped * math.pi / 180.0);
    final y = (0.5 - math.log((1 + sinLat) / (1 - sinLat)) / (4 * math.pi)) *
        worldSize(zoom);
    return y.clamp(0.0, worldSize(zoom));
  }

  /// Longitude for pixel [x] at [zoom].
  static double lonForX(double x, int zoom) =>
      x / worldSize(zoom) * 360.0 - 180.0;

  /// Latitude for pixel [y] at [zoom].
  static double latForY(double y, int zoom) {
    final n = math.pi - 2.0 * math.pi * y / worldSize(zoom);
    return 180.0 / math.pi * math.atan(0.5 * (math.exp(n) - math.exp(-n)));
  }
}

/// Offline map for incoming SOS telemetry.
///
/// Renders the coordinates carried inside an iBFS distress packet with **no
/// internet connection**, using raster tiles already on the device:
///
/// ```
/// <app documents>/offline_tiles/<z>/<x>/<y>.png     (preferred)
/// assets/offline_tiles/<z>/<x>/<y>.png              (optional fallback)
/// ```
///
/// This is the standard XYZ layout every MBTiles archive uses one table per
/// zoom in, so an `.mbtiles` file can be exported straight into this folder
/// (`sqlite3 world.mbtiles "select zoom_level,tile_column,tile_row,tile_data"`
/// — see docs/ADDITIONAL_FEATURES.md §6).
///
/// When no tiles are installed the widget still does something useful: it draws
/// a Mercator graticule, keeps pan and zoom working, and plots the marker at the
/// correct position, so the operator can see where the distress call came from
/// even on a bare device.
class OfflineMapView extends StatefulWidget {
  const OfflineMapView({
    super.key,
    required this.lat,
    required this.lon,
    this.label,
    this.initialZoom = 14.0,
    this.minZoom = 2.0,
    this.maxZoom = 18.0,
  });

  /// Latitude of the distress position.
  final double lat;

  /// Longitude of the distress position.
  final double lon;

  /// Optional caption, e.g. the sender's name.
  final String? label;

  final double initialZoom;
  final double minZoom;
  final double maxZoom;

  @override
  State<OfflineMapView> createState() => _OfflineMapViewState();
}

class _OfflineMapViewState extends State<OfflineMapView> {
  late double _centerLat = widget.lat;
  late double _centerLon = widget.lon;
  late double _zoom = widget.initialZoom;

  /// Zoom the current gesture started from, so pinch scaling is absolute
  /// rather than compounding every frame.
  double _zoomAtGestureStart = 0;

  /// Resolved tile locations. Null until the file system has been probed.
  Directory? _fileTileRoot;
  bool _assetTiles = false;
  bool _probed = false;

  @override
  void initState() {
    super.initState();
    _probeTiles();
  }

  @override
  void didUpdateWidget(covariant OfflineMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A new distress position should recentre the map.
    if (oldWidget.lat != widget.lat || oldWidget.lon != widget.lon) {
      setState(() {
        _centerLat = widget.lat;
        _centerLon = widget.lon;
      });
    }
  }

  Future<void> _probeTiles() async {
    Directory? root;
    try {
      final docs = await getApplicationDocumentsDirectory();
      final dir = Directory('${docs.path}/offline_tiles');
      if (await dir.exists()) root = dir;
    } catch (_) {
      // Documents directory unavailable — fall back to asset tiles.
    }
    if (!mounted) return;
    setState(() {
      _fileTileRoot = root;
      // Only fall back to bundled assets when there is no local tile store.
      _assetTiles = root == null;
      _probed = true;
    });
  }

  int get _tileZoom => _zoom.floor().clamp(
        widget.minZoom.floor(),
        widget.maxZoom.floor(),
      );

  /// Fractional zoom applied on top of the integer tile zoom.
  double get _scale => math.pow(2.0, _zoom - _tileZoom).toDouble();

  void _recenter() {
    setState(() {
      _centerLat = widget.lat;
      _centerLon = widget.lon;
      _zoom = widget.initialZoom;
    });
  }

  void _panBy(double dxScreen, double dyScreen) {
    final z = _tileZoom;
    final world = MercatorProjection.worldSize(z);
    final cx = MercatorProjection.xForLon(_centerLon, z) - dxScreen / _scale;
    final cy = MercatorProjection.yForLat(_centerLat, z) - dyScreen / _scale;
    _centerLon =
        MercatorProjection.lonForX(cx.clamp(0.0, world - 0.001), z);
    _centerLat =
        MercatorProjection.latForY(cy.clamp(0.0, world - 0.001), z);
  }

  /// Whether a street-level tile store was found (drives the hint banner).
  bool get _hasTiles => _fileTileRoot != null || _assetTiles;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: Container(
        color: const Color(0xFF0E1418),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final size = Size(constraints.maxWidth, constraints.maxHeight);
            return GestureDetector(
              onScaleStart: (_) => _zoomAtGestureStart = _zoom,
              onScaleUpdate: (details) {
                setState(() {
                  if (details.focalPointDelta != Offset.zero) {
                    _panBy(details.focalPointDelta.dx,
                        details.focalPointDelta.dy);
                  }
                  if (details.scale != 1.0 && _zoomAtGestureStart > 0) {
                    _zoom = (_zoomAtGestureStart +
                            _log2(details.scale))
                        .clamp(widget.minZoom, widget.maxZoom);
                  }
                });
              },
              onDoubleTap: () {
                setState(() {
                  _zoom = (_zoom + 1).clamp(widget.minZoom, widget.maxZoom);
                });
              },
              child: Stack(
                children: [
                  // Graticule / empty-state background.
                  Positioned.fill(
                    child: CustomPaint(
                      painter: _GraticulePainter(
                        centerLat: _centerLat,
                        centerLon: _centerLon,
                        zoom: _tileZoom,
                        scale: _scale,
                      ),
                    ),
                  ),

                  // Raster tiles.
                  if (_probed) ..._buildTiles(size),

                  // Distress marker at the exact coordinate.
                  ..._buildMarker(size),

                  // No-tiles hint.
                  if (_probed && !_hasTiles)
                    Positioned(
                      left: 8,
                      right: 8,
                      top: 8,
                      child: _HintBanner(
                        text: 'No offline tiles installed — showing '
                            'coordinates on a grid. Drop an XYZ tile set in '
                            'the app documents folder to see real map detail.',
                      ),
                    ),

                  Positioned(
                    right: 8,
                    bottom: 8,
                    child: _MapControls(
                      zoom: _zoom,
                      onZoomIn: () => setState(() {
                        _zoom = (_zoom + 1).clamp(widget.minZoom, widget.maxZoom);
                      }),
                      onZoomOut: () => setState(() {
                        _zoom = (_zoom - 1).clamp(widget.minZoom, widget.maxZoom);
                      }),
                      onRecenter: _recenter,
                    ),
                  ),

                  Positioned(
                    left: 8,
                    bottom: 8,
                    child: _CoordinateCaption(
                      lat: widget.lat,
                      lon: widget.lon,
                      label: widget.label,
                      zoom: _tileZoom,
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  /// Positioned tile images covering the viewport.
  List<Widget> _buildTiles(Size size) {
    final z = _tileZoom;
    final scale = _scale;
    final tileCount = 1 << z;

    final worldSize = MercatorProjection.worldSize(z);
    final centerX = MercatorProjection.xForLon(_centerLon, z);
    final centerY = MercatorProjection.yForLat(_centerLat, z);

    // Viewport rectangle in world pixels at [z].
    final halfW = size.width / (2 * scale);
    final halfH = size.height / (2 * scale);
    final left = (centerX - halfW).clamp(0.0, worldSize);
    final top = (centerY - halfH).clamp(0.0, worldSize);

    final firstTileX = (left / MercatorProjection.tileSize).floor();
    final firstTileY = (top / MercatorProjection.tileSize).floor();
    final lastTileX = ((left + size.width / scale) /
            MercatorProjection.tileSize)
        .floor();
    final lastTileY = ((top + size.height / scale) /
            MercatorProjection.tileSize)
        .floor();

    final widgets = <Widget>[];
    final side = MercatorProjection.tileSize * scale;

    for (var tx = firstTileX; tx <= lastTileX; tx++) {
      // Wrap X around the antimeridian; clamp Y (there is no tile past the
      // poles).
      final wrappedX = ((tx % tileCount) + tileCount) % tileCount;
      for (var ty = firstTileY; ty <= lastTileY; ty++) {
        if (ty < 0 || ty >= tileCount) continue;
        final screenLeft =
            (tx * MercatorProjection.tileSize - left) * scale;
        final screenTop = (ty * MercatorProjection.tileSize - top) * scale;

        widgets.add(Positioned(
          left: screenLeft,
          top: screenTop,
          width: side,
          height: side,
          child: _TileImage(
            zoom: z,
            x: wrappedX,
            y: ty,
            fileRoot: _fileTileRoot,
          ),
        ));
      }
    }
    return widgets;
  }

  List<Widget> _buildMarker(Size size) {
    // With the centre at the map centre, the marker sits at the viewport
    // centre offset by however far the user has panned.
    final z = _tileZoom;
    final scale = _scale;
    final centerX = MercatorProjection.xForLon(_centerLon, z);
    final centerY = MercatorProjection.yForLat(_centerLat, z);
    final markerX = MercatorProjection.xForLon(widget.lon, z);
    final markerY = MercatorProjection.yForLat(widget.lat, z);

    final dx = (markerX - centerX) * scale + size.width / 2;
    final dy = (markerY - centerY) * scale + size.height / 2;

    // Keep the pin attached to the edge when panned off-screen rather than
    // letting it vanish and hide where the distress call is.
    final pinnedX = dx.clamp(16.0, math.max(16.0, size.width - 16.0));
    final pinnedY = dy.clamp(32.0, math.max(32.0, size.height - 8.0));
    final offScreen = dx != pinnedX || dy != pinnedY;

    return [
      Positioned(
        left: pinnedX - 20,
        top: pinnedY - 40,
        child: Opacity(
          opacity: offScreen ? 0.55 : 1.0,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: iTantraTheme.danger.withValues(alpha: 0.25),
                ),
              ),
              const Icon(
                Icons.location_on,
                size: 40,
                color: iTantraTheme.danger,
              ),
            ],
          ),
        ),
      ),
    ];
  }

  static double _log2(double v) =>
      v <= 0 ? 0 : math.log(v) / math.ln2;
}

/// A single tile, read from the local tile store or bundled assets.
class _TileImage extends StatelessWidget {
  const _TileImage({
    required this.zoom,
    required this.x,
    required this.y,
    required this.fileRoot,
  });

  final int zoom;
  final int x;
  final int y;
  final Directory? fileRoot;

  /// Missing tiles render nothing, letting the graticule show through — a
  /// blank square is a much better failure mode than a red error box on a map
  /// that is trying to show someone where a distress call came from.
  Widget _missing() => const SizedBox.shrink();

  @override
  Widget build(BuildContext context) {
    const filter = FilterQuality.medium;
    final root = fileRoot;
    if (root != null) {
      return Image.file(
        File('${root.path}/$zoom/$x/$y.png'),
        fit: BoxFit.fill,
        filterQuality: filter,
        gaplessPlayback: true,
        errorBuilder: (_, _, _) => _missing(),
      );
    }
    return Image.asset(
      'assets/offline_tiles/$zoom/$x/$y.png',
      fit: BoxFit.fill,
      filterQuality: filter,
      gaplessPlayback: true,
      errorBuilder: (_, _, _) => _missing(),
    );
  }
}

/// Degree grid drawn under the tiles.
class _GraticulePainter extends CustomPainter {
  const _GraticulePainter({
    required this.centerLat,
    required this.centerLon,
    required this.zoom,
    required this.scale,
  });

  final double centerLat;
  final double centerLon;
  final int zoom;
  final double scale;

  @override
  void paint(Canvas canvas, Size size) {
    final gridPaint = Paint()
      ..color = iTantraTheme.border.withValues(alpha: 0.5)
      ..strokeWidth = 0.7;

    // Tile boundaries.
    final tileScreen = MercatorProjection.tileSize * scale;
    final centerX = MercatorProjection.xForLon(centerLon, zoom);
    final centerY = MercatorProjection.yForLat(centerLat, zoom);
    final originX =
        (centerX - size.width / (2 * scale)) / MercatorProjection.tileSize;
    final originY =
        (centerY - size.height / (2 * scale)) / MercatorProjection.tileSize;

    final firstX = originX.floorToDouble();
    final firstY = originY.floorToDouble();
    for (var i = firstX;; i++) {
      final sx = (i - originX) * tileScreen;
      if (sx > size.width) break;
      canvas.drawLine(Offset(sx, 0), Offset(sx, size.height), gridPaint);
    }
    for (var j = firstY;; j++) {
      final sy = (j - originY) * tileScreen;
      if (sy > size.height) break;
      canvas.drawLine(Offset(0, sy), Offset(size.width, sy), gridPaint);
    }

    // Crosshair on the exact distress coordinate.
    final markerX =
        (MercatorProjection.xForLon(centerLon, zoom) - centerX) * scale +
            size.width / 2;
    final markerY =
        (MercatorProjection.yForLat(centerLat, zoom) - centerY) * scale +
            size.height / 2;
    final crosshair = Paint()
      ..color = iTantraTheme.danger.withValues(alpha: 0.35)
      ..strokeWidth = 1;
    canvas.drawLine(Offset(markerX - 18, markerY),
        Offset(markerX + 18, markerY), crosshair);
    canvas.drawLine(Offset(markerX, markerY - 18),
        Offset(markerX, markerY + 18), crosshair);
  }

  @override
  bool shouldRepaint(covariant _GraticulePainter old) =>
      old.centerLat != centerLat ||
      old.centerLon != centerLon ||
      old.zoom != zoom ||
      old.scale != scale;
}

class _HintBanner extends StatelessWidget {
  const _HintBanner({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.65),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: iTantraTheme.border),
      ),
      child: Text(
        text,
        style: const TextStyle(
          fontSize: 10,
          color: iTantraTheme.textSecondary,
          height: 1.3,
        ),
      ),
    );
  }
}

class _CoordinateCaption extends StatelessWidget {
  const _CoordinateCaption({
    required this.lat,
    required this.lon,
    required this.label,
    required this.zoom,
  });

  final double lat;
  final double lon;
  final String? label;
  final int zoom;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.68),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: iTantraTheme.danger.withValues(alpha: 0.5),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (label != null && label!.isNotEmpty)
            Text(
              label!,
              style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: Colors.white,
              ),
            ),
          Text(
            '${lat.toStringAsFixed(5)}, ${lon.toStringAsFixed(5)}',
            style: const TextStyle(
              fontSize: 11,
              fontFamily: 'monospace',
              color: iTantraTheme.saffron,
            ),
          ),
          Text(
            'z$zoom · offline',
            style: const TextStyle(
              fontSize: 9,
              color: iTantraTheme.textMuted,
            ),
          ),
        ],
      ),
    );
  }
}

class _MapControls extends StatelessWidget {
  const _MapControls({
    required this.zoom,
    required this.onZoomIn,
    required this.onZoomOut,
    required this.onRecenter,
  });

  final double zoom;
  final VoidCallback onZoomIn;
  final VoidCallback onZoomOut;
  final VoidCallback onRecenter;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _MapButton(icon: Icons.my_location, onTap: onRecenter),
        const SizedBox(height: 6),
        _MapButton(icon: Icons.add, onTap: onZoomIn),
        const SizedBox(height: 6),
        _MapButton(icon: Icons.remove, onTap: onZoomOut),
      ],
    );
  }
}

class _MapButton extends StatelessWidget {
  const _MapButton({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        width: 34,
        height: 34,
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.7),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: iTantraTheme.border),
        ),
        child: Icon(icon, size: 18, color: iTantraTheme.saffron),
      ),
    );
  }
}
