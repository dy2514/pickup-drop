package com.example.pickupdrop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// App-owned Compose styling informed by the Pleos Connect design guide.
// These are app colors, not imported or certified Pleos SDK tokens.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary=Color(0xFFF5F5F5), onPrimary=Color(0xFF151515),
        primaryContainer=Color(0xFF343434), onPrimaryContainer=Color.White,
        secondaryContainer=Color(0xFF302B20), onSecondaryContainer=Color(0xFFFFDF9C),
        background=Color(0xFF101010), onBackground=Color(0xFFF5F5F5),
        surface=Color(0xFF101010), onSurface=Color(0xFFF5F5F5),
        surfaceVariant=Color(0xFF202020), onSurfaceVariant=Color(0xFFD0D0D0),
        outline=Color(0xFF949494), outlineVariant=Color(0xFF484848)
    ) else lightColorScheme(
        primary=Color(0xFF171717), onPrimary=Color.White,
        primaryContainer=Color(0xFFDDDDDD), onPrimaryContainer=Color(0xFF111111),
        secondaryContainer=Color(0xFFFFEBC4), onSecondaryContainer=Color(0xFF574000),
        background=Color(0xFFF7F7F7), onBackground=Color(0xFF171717),
        surface=Color(0xFFF7F7F7), onSurface=Color(0xFF171717),
        surfaceVariant=Color.White, onSurfaceVariant=Color(0xFF505050),
        outline=Color(0xFF727272), outlineVariant=Color(0xFFD0D0D0)
    )
    val base=Typography()
    val type=base.copy(
        headlineMedium=base.headlineMedium.copy(fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.SemiBold),
        headlineSmall=base.headlineSmall.copy(fontSize=30.sp,lineHeight=38.sp),
        titleLarge=base.titleLarge.copy(fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Medium),
        titleMedium=base.titleMedium.copy(fontSize=24.sp,lineHeight=32.sp),
        bodyLarge=base.bodyLarge.copy(fontSize=24.sp,lineHeight=34.sp),
        bodyMedium=base.bodyMedium.copy(fontSize=22.sp,lineHeight=32.sp),
        bodySmall=base.bodySmall.copy(fontSize=20.sp,lineHeight=28.sp),
        labelLarge=base.labelLarge.copy(fontSize=22.sp,lineHeight=30.sp)
    )
    MaterialTheme(colorScheme=colors,typography=type,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp))) {
        // Enforce a large minimum interaction area for buttons, chips and other Material controls.
        androidx.compose.runtime.CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 64.dp,content=content)
    }
}

@Composable
fun PickupNavigationItem(index: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(12.dp),
        color=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor=if(selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min=72.dp).padding(horizontal=20.dp,vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("0${index+1}",style=MaterialTheme.typography.labelLarge)
            Text(label,style=MaterialTheme.typography.labelLarge)
        }
    }
}
