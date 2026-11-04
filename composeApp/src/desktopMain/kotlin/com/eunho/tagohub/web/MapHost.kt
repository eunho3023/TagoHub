package com.eunho.tagohub.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** PC 버전: 카카오 웹 지도는 브라우저에서만 돼서 OpenStreetMap 지도 */
@Composable
actual fun MapHost(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier,
    lines: List<MapLine>,
    dots: List<MapDot>,
    onDot: (MapDot) -> Unit,
    onTap: () -> Unit,
) = MapView(state, tiles, modifier, lines, dots, onDot, onTap)
