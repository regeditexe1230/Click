package com.yjc.click.ui.program

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yjc.click.R
import com.yjc.click.ui.theme.ClickText

/**
 * 空画布提示：图上只有开始节点时浮在中间，告诉用户怎么开始。
 * 纯提示，不吃触摸（外面那层仍然能拖动画布）。
 */
@Composable
internal fun NodeGraphEmptyHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ClickText(
            text = stringResource(R.string.node_graph_empty_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 底部一行提示（连线失败原因等），2.6 秒后自己消失。 */
@Composable
internal fun NodeGraphToast(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            ClickText(
                text = text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
