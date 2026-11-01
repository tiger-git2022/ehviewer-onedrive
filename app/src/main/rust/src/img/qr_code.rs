// Fork: an ad page is a page with a QR code that can be read, or that has its dotted timing lines.
// Upstream took any three corner squares in a right angle for one: a picture with three such squares was blocked,
// and a light code on a dark background, or a stretched one, was not.
use super::core::ImageConsumer;
use crate::img::core::CustomPixel;
use anyhow::Result;
use image::{GrayImage, ImageBuffer};
use rxing::common::reedsolomon::{PredefinedGenericGF, ReedSolomonDecoder};
use rxing::common::{BitMatrix, DetectorRXingResult, HybridBinarizer};
use rxing::multi::qrcode::detector::MultiFinderPatternFinder;
use rxing::qrcode::decoder::{BitMatrixParser, DataBlock};
use rxing::qrcode::detector::{Detector, FinderPatternFinder, FinderPatternInfo};
use rxing::{BinaryBitmap, DecodeHintValue, DecodeHints, Luma8LuminanceSource, LuminanceSource};

fn image_buffer_to_luma8<P: CustomPixel>(src: &ImageBuffer<P, &[P::Subpixel]>) -> Result<Luma8LuminanceSource> {
    let (w, h) = src.dimensions();
    let mut dst = GrayImage::new(w, h);
    for (to, from) in dst.pixels_mut().zip(src.pixels()) {
        *to = from.to_luma8();
    }
    Ok(Luma8LuminanceSource::new(dst.into_raw(), w, h)?)
}

/// The error correction of the code checks out. The text itself is not read:
/// rxing is built without character sets, and reading a text panics there.
fn error_correction_ok(parser: &mut BitMatrixParser, rs: &ReedSolomonDecoder) -> bool {
    let mut check = || -> rxing::common::Result<()> {
        let version = parser.readVersion()?;
        let ec_level = parser.readFormatInformation()?.getErrorCorrectionLevel();
        let codewords = parser.readCodewords()?;
        for block in DataBlock::getDataBlocks(&codewords, version, ec_level)? {
            let mut bytes = block.getCodewords().to_vec();
            let ec_count = (bytes.len() - block.getNumDataCodewords() as usize) as i32;
            rs.decode(&mut bytes, ec_count)?;
        }
        Ok(())
    };
    check().is_ok()
}

fn readable(bits: &BitMatrix, rs: &ReedSolomonDecoder) -> bool {
    let Ok(mut parser) = BitMatrixParser::new(bits.clone()) else {
        return false;
    };
    if error_correction_ok(&mut parser, rs) {
        return true;
    }
    // Mirrored, as the rxing decoder tries it
    if parser.remask().is_err() {
        return false;
    }
    parser.setMirror(true);
    if parser.readVersion().is_err() || parser.readFormatInformation().is_err() {
        return false;
    }
    parser.mirror();
    error_correction_ok(&mut parser, rs)
}

/// The dotted lines between the corner squares are there, in nine places out of ten:
/// a code with a sticker over its middle still counts, three lone squares do not
fn timing_lines_ok(bits: &BitMatrix) -> bool {
    let dim = bits.getWidth();
    if dim < 21 || bits.getHeight() != dim {
        return false;
    }
    let (mut good, mut total) = (0, 0);
    for i in 8..dim - 8 {
        let dark = i % 2 == 0;
        good += (bits.get(i, 6) == dark) as u32 + (bits.get(6, i) == dark) as u32;
        total += 2;
    }
    good * 10 >= total * 9
}

fn has_qr_code(matrix: &BitMatrix, rs: &ReedSolomonDecoder) -> bool {
    let hints = DecodeHints::default().with(DecodeHintValue::TryHarder(true));
    // Ad pages often hold several codes: each one is a candidate, plus the best three squares of the page
    let mut candidates: Vec<FinderPatternInfo> = MultiFinderPatternFinder::new(matrix, None).findMulti(&hints).unwrap_or_default();
    if let Ok(info) = FinderPatternFinder::new(matrix).find(&hints) {
        candidates.push(info);
    }
    let detector = Detector::new(matrix);
    candidates.into_iter().any(|info| detector.processFinderPatternInfo(info).is_ok_and(|code| timing_lines_ok(code.getBits()) || readable(code.getBits(), rs)))
}

fn has_qr_code_in(mut source: Luma8LuminanceSource) -> bool {
    let rs = ReedSolomonDecoder::new(PredefinedGenericGF::QrCodeField256.into());
    for _ in 0..2 {
        let bitmap = BinaryBitmap::new(HybridBinarizer::new(source.clone()));
        if has_qr_code(bitmap.get_black_matrix(), &rs) {
            return true;
        }
        // Then a light code on a dark background
        source.invert();
    }
    false
}

pub struct QrCode;

impl ImageConsumer<bool> for QrCode {
    fn apply<P: CustomPixel>(self, image: &ImageBuffer<P, &[P::Subpixel]>) -> Result<bool> {
        Ok(has_qr_code_in(image_buffer_to_luma8(image)?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(name: &str) -> bool {
        let path = format!("{}/test_data/qr/{name}", env!("CARGO_MANIFEST_DIR"));
        let image = image::open(path).unwrap().to_luma8();
        let (w, h) = image.dimensions();
        has_qr_code_in(Luma8LuminanceSource::new(image.into_raw(), w, h).unwrap())
    }

    #[test]
    fn finds_ads() {
        for name in ["single.png", "four.png", "logo.png", "inverted.png", "stretched.png", "covered.png", "blur.jpg"] {
            assert!(check(name), "{name}");
        }
    }

    #[test]
    fn leaves_pictures() {
        for name in ["three_squares.png", "screentone.png", "checker.png"] {
            assert!(!check(name), "{name}");
        }
    }
}
