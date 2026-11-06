// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>

#include "common/common_types.h"
#include "common/input.h"

namespace InputCommon::HdRumble {

/// Encode a normalised amplitude into the Switch HD rumble high-band code.
u8 EncodeHighAmplitude(f32 amplitude);

/// Encode a normalised amplitude into the Switch HD rumble low-band code.
u16 EncodeLowAmplitude(f32 amplitude);

/// Encode a frequency in Hz into the Switch HD rumble high-band frequency code.
u16 EncodeHighFrequency(f32 frequency);

/// Encode a frequency in Hz into the Switch HD rumble low-band frequency code.
u8 EncodeLowFrequency(f32 frequency);

/// Build a 10-byte output report (0x10 + counter + left/right rumble).
/// [boost] lifts quiet amplitudes to compensate for weaker third-party LRAs.
std::array<u8, 10> BuildPacket(const Common::Input::VibrationStatus& vibration, u8 counter,
                               bool boost = false);

} // namespace InputCommon::HdRumble
