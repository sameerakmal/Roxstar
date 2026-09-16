#include "IEffect.h"

#include "EchoEffect.h"
#include "PitchShiftEffect.h"
#include "ReverbEffect.h"

namespace roxstar::effects {

bool isValidEffectType(int32_t value) {
    // No `default` case: adding a new EffectType enumerator without updating
    // this switch triggers a compiler warning here as a reminder.
    switch (static_cast<EffectType>(value)) {
        case EffectType::None:
        case EffectType::Echo:
        case EffectType::Reverb:
        case EffectType::PitchShift:
            return true;
    }
    return false;
}

std::unique_ptr<IEffect> createEffect(EffectType type) {
    switch (type) {
        case EffectType::Echo:
            return std::make_unique<EchoEffect>();
        case EffectType::Reverb:
            return std::make_unique<ReverbEffect>();
        case EffectType::PitchShift:
            return std::make_unique<PitchShiftEffect>();
        case EffectType::None:
            return nullptr;
    }
    return nullptr;
}

}  // namespace roxstar::effects
