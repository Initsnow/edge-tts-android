use rubato::{
    Resampler, SincFixedIn, SincInterpolationParameters, SincInterpolationType, WindowFunction,
};

pub struct BeatriceResampler {
    in_resampler: SincFixedIn<f32>,
    out_resampler: SincFixedIn<f32>,

    in_sample_rate: f64,
    out_sample_rate: f64,
    in_channel: u32,
    out_channel: u32,
}

impl BeatriceResampler {
    pub fn new(
        in_sample_rate: f64,
        out_sample_rate: f64,
        in_channel: u32,
        out_channel: u32,
    ) -> Self {
        let in_hop = (in_sample_rate / 100.0).round() as usize;
        let in_resampler = SincFixedIn::<f32>::new(
            16000.0 / in_sample_rate,
            2.0,
            SincInterpolationParameters {
                sinc_len: 128,
                f_cutoff: 0.9,
                interpolation: SincInterpolationType::Linear,
                oversampling_factor: 64,
                window: WindowFunction::BlackmanHarris2,
            },
            in_hop,
            1,
        )
        .unwrap();

        let out_resampler = SincFixedIn::<f32>::new(
            out_sample_rate / 24000.0,
            2.0,
            SincInterpolationParameters {
                sinc_len: 128,
                f_cutoff: 0.9,
                interpolation: SincInterpolationType::Linear,
                oversampling_factor: 64,
                window: WindowFunction::BlackmanHarris2,
            },
            240,
            1,
        )
        .unwrap();

        Self {
            in_resampler,
            out_resampler,
            in_sample_rate,
            out_sample_rate,
            in_channel,
            out_channel,
        }
    }

    pub fn convert_to_beatrice_input(&mut self, input: &[f32]) -> Vec<f32> {
        let hop_size = (self.in_sample_rate / 100.0).round() as usize;
        let mono_storage;
        let mono_slice: &[f32] = match self.in_channel {
            1 => input,
            2 => {
                let mut mono = Vec::with_capacity(input.len() / 2);
                for i in 0..(input.len() / 2) {
                    mono.push((input[i * 2] + input[i * 2 + 1]) * 0.5);
                }
                mono_storage = mono;
                &mono_storage
            }
            _ => panic!("in_channel must be 1 or 2"),
        };

        let estimated_out_len = ((mono_slice.len() as f64) * 16000.0 / self.in_sample_rate).ceil() as usize + 512;
        let mut resampled_all = Vec::with_capacity(estimated_out_len);
        let mut pad_buf = vec![0.0f32; hop_size];

        for chunk in mono_slice.chunks(hop_size) {
            let res = if chunk.len() == hop_size {
                self.in_resampler.process(&[chunk], None)
            } else {
                pad_buf.fill(0.0);
                pad_buf[..chunk.len()].copy_from_slice(chunk);
                self.in_resampler.process(&[&pad_buf[..]], None)
            };

            if let Ok(mut out) = res {
                if !out.is_empty() {
                    resampled_all.append(&mut out.remove(0));
                }
            }
        }
        resampled_all
    }

    pub fn convert_from_beatrice_output(&mut self, processed: &[f32]) -> Vec<f32> {
        if (self.out_sample_rate - 24000.0).abs() < 1.0 && self.out_channel == 1 {
            return processed.to_vec();
        }

        let estimated_len = ((processed.len() as f64) * self.out_sample_rate / 24000.0).ceil() as usize * self.out_channel as usize + 512;
        let mut all_out = Vec::with_capacity(estimated_len);
        let mut pad_buf = vec![0.0f32; 240];

        for chunk in processed.chunks(240) {
            let res = if chunk.len() == 240 {
                self.out_resampler.process(&[chunk], None)
            } else {
                pad_buf.fill(0.0);
                pad_buf[..chunk.len()].copy_from_slice(chunk);
                self.out_resampler.process(&[&pad_buf[..]], None)
            };

            if let Ok(mut out) = res {
                if !out.is_empty() {
                    let mono = out.remove(0);
                    match self.out_channel {
                        1 => all_out.extend_from_slice(&mono),
                        2 => {
                            for &s in &mono {
                                all_out.push(s);
                                all_out.push(s);
                            }
                        }
                        _ => panic!("out_channel must be 1 or 2"),
                    }
                }
            }
        }
        all_out
    }
}
