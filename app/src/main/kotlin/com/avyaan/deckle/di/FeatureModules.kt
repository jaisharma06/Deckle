package com.avyaan.deckle.di

import com.avyaan.deckle.data.export.AndroidJpegSizeProbe
import com.avyaan.deckle.data.export.PdfBoxComposer
import com.avyaan.deckle.data.export.WorkManagerExportScheduler
import com.avyaan.deckle.domain.export.ExportScheduler
import com.avyaan.deckle.domain.export.JpegSizeProbe
import com.avyaan.deckle.domain.export.PdfComposer
import com.avyaan.deckle.data.imaging.common.AndroidImageNormalizer
import com.avyaan.deckle.data.imaging.common.AndroidImageRotator
import com.avyaan.deckle.data.imaging.common.AndroidThumbnailMaker
import com.avyaan.deckle.data.imaging.engine.EngineSelector
import com.avyaan.deckle.data.imaging.opencv.BlackWhiteFilter
import com.avyaan.deckle.data.imaging.opencv.ColourFilter
import com.avyaan.deckle.data.imaging.opencv.EcoFilter
import com.avyaan.deckle.data.imaging.opencv.EnhancementFilter
import com.avyaan.deckle.data.imaging.opencv.EnhancementModeKey
import com.avyaan.deckle.data.imaging.opencv.VividFilter
import com.avyaan.deckle.data.imaging.opencv.WarmFilter
import com.avyaan.deckle.data.imaging.opencv.CoolFilter
import com.avyaan.deckle.data.imaging.opencv.SepiaFilter
import com.avyaan.deckle.data.imaging.opencv.VintageFilter
import com.avyaan.deckle.data.imaging.opencv.MatteFilter
import com.avyaan.deckle.data.imaging.opencv.NoirFilter
import com.avyaan.deckle.data.imaging.opencv.SketchFilter
import com.avyaan.deckle.data.imaging.opencv.BlueprintFilter
import com.avyaan.deckle.data.imaging.opencv.NegativeFilter
import com.avyaan.deckle.data.imaging.opencv.GreyscaleFilter
import com.avyaan.deckle.data.imaging.opencv.LightenFilter
import com.avyaan.deckle.data.imaging.opencv.MagicFilter
import com.avyaan.deckle.data.imaging.opencv.OriginalFilter
import com.avyaan.deckle.data.imaging.opencv.PhotoFilter
import com.avyaan.deckle.data.ocr.MlKitTextRecognizer
import com.avyaan.deckle.data.ocr.WorkManagerOcrScheduler
import com.avyaan.deckle.domain.imaging.EnhancementMode
import com.avyaan.deckle.domain.imaging.ImageNormalizer
import com.avyaan.deckle.domain.imaging.ImageRotator
import com.avyaan.deckle.domain.imaging.PageDetector
import com.avyaan.deckle.domain.imaging.PageEnhancer
import com.avyaan.deckle.domain.imaging.PerspectiveCorrector
import com.avyaan.deckle.domain.imaging.ThumbnailMaker
import com.avyaan.deckle.domain.ocr.OcrScheduler
import com.avyaan.deckle.domain.ocr.TextRecognizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * Imaging: consumers inject the narrow ports; only this module knows about the engine factory.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImagingModule {
    @Provides fun detector(selector: EngineSelector): PageDetector = selector.engine.detector()
    @Provides fun corrector(selector: EngineSelector): PerspectiveCorrector = selector.engine.corrector()
    @Provides fun enhancer(selector: EngineSelector): PageEnhancer = selector.engine.enhancer()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ImagingBindings {
    @Binds abstract fun normalizer(impl: AndroidImageNormalizer): ImageNormalizer
    @Binds abstract fun thumbnails(impl: AndroidThumbnailMaker): ThumbnailMaker
    @Binds abstract fun rotator(impl: AndroidImageRotator): ImageRotator

    // Strategy map for FR-31. Adding a mode = one new filter class + one binding here.
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.ORIGINAL) abstract fun original(f: OriginalFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.COLOUR) abstract fun colour(f: ColourFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.GREYSCALE) abstract fun greyscale(f: GreyscaleFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.BLACK_AND_WHITE) abstract fun blackWhite(f: BlackWhiteFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.MAGIC) abstract fun magic(f: MagicFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.LIGHTEN) abstract fun lighten(f: LightenFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.PHOTO) abstract fun photo(f: PhotoFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.ECO) abstract fun eco(f: EcoFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.VIVID) abstract fun vivid(f: VividFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.WARM) abstract fun warm(f: WarmFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.COOL) abstract fun cool(f: CoolFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.SEPIA) abstract fun sepia(f: SepiaFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.VINTAGE) abstract fun vintage(f: VintageFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.MATTE) abstract fun matte(f: MatteFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.NOIR) abstract fun noir(f: NoirFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.SKETCH) abstract fun sketch(f: SketchFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.BLUEPRINT) abstract fun blueprint(f: BlueprintFilter): EnhancementFilter
    @Binds @IntoMap @EnhancementModeKey(EnhancementMode.NEGATIVE) abstract fun negative(f: NegativeFilter): EnhancementFilter
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds abstract fun recognizer(impl: MlKitTextRecognizer): TextRecognizer
    @Binds abstract fun scheduler(impl: WorkManagerOcrScheduler): OcrScheduler
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds abstract fun composer(impl: PdfBoxComposer): PdfComposer
    @Binds abstract fun sizeProbe(impl: AndroidJpegSizeProbe): JpegSizeProbe
    @Binds abstract fun scheduler(impl: WorkManagerExportScheduler): ExportScheduler
}
