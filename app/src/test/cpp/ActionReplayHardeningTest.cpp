#include <cstdlib>
#include <initializer_list>
#include <iostream>
#include <string_view>
#include <vector>

#include "ActionReplayCodeParser.h"
#include "ARCodeValidator.h"

namespace
{

using melonDS::u32;

int checks = 0;

void Check(bool condition, const char* message)
{
    checks++;
    if (!condition)
    {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void ExpectCode(std::string_view input, std::initializer_list<u32> expected)
{
    const auto result = MelonDSAndroid::ParseActionReplayCode(input);
    Check(result.IsValid(), "expected valid Action Replay text");
    Check(result.Code == std::vector<u32>(expected), "parsed words differ");
}

void ExpectParseError(std::string_view input, MelonDSAndroid::ActionReplayParseError error)
{
    const auto result = MelonDSAndroid::ParseActionReplayCode(input);
    Check(!result.IsValid(), "expected invalid Action Replay text");
    Check(result.Error == error, "unexpected parser error");
}

void ExpectStructure(const std::vector<u32>& code, melonDS::ARCodeValidationError error)
{
    const auto result = melonDS::ValidateARCode(code);
    Check(result.Error == error, "unexpected Action Replay structure result");
}

}

int main()
{
    using MelonDSAndroid::ActionReplayParseError;
    using melonDS::ARCodeValidationError;

    ExpectCode("12345678 9ABCDEF0", {0x12345678, 0x9ABCDEF0});
    ExpectCode(
        "12345678 9ABCDEF0 00000000 FFFFFFFF",
        {0x12345678, 0x9ABCDEF0, 0x00000000, 0xFFFFFFFF}
    );
    ExpectCode("abcdef01 23456789", {0xABCDEF01, 0x23456789});
    ExpectCode("12345678    9ABCDEF0", {0x12345678, 0x9ABCDEF0});
    ExpectCode("  12345678 9ABCDEF0  ", {0x12345678, 0x9ABCDEF0});
    ExpectCode("\t12345678\n9ABCDEF0\r\f\v", {0x12345678, 0x9ABCDEF0});

    ExpectParseError("", ActionReplayParseError::Empty);
    ExpectParseError(" \t\r\n", ActionReplayParseError::Empty);
    ExpectParseError("ZZZZZZZZ 00000000", ActionReplayParseError::InvalidToken);
    ExpectParseError("00000000 1234ZZZZ", ActionReplayParseError::InvalidToken);
    ExpectParseError("1234ZZZZ", ActionReplayParseError::InvalidToken);
    ExpectParseError("1234567 9ABCDEF0", ActionReplayParseError::InvalidToken);
    ExpectParseError("12345678 9ABCDEF0 DEADBEEF", ActionReplayParseError::OddWordCount);
    ExpectParseError("100000000 00000000", ActionReplayParseError::ValueOutOfRange);

    ExpectCode(
        "922822A8 00001555 122822A8 00001C72 D2000000 00000000",
        {0x922822A8, 0x00001555, 0x122822A8, 0x00001C72, 0xD2000000, 0x00000000}
    );
    ExpectCode(
        "520209C4 E59D0094 020209C8 E3A09C15 0200D03C 00001C71 D2000000 00000000 "
        "520B21E8 E59F00E8 020B227C E3A02B07 D2000000 00000000 5211530C E59F205C "
        "02115370 00001C71 D2000000 00000000",
        {
            0x520209C4, 0xE59D0094, 0x020209C8, 0xE3A09C15,
            0x0200D03C, 0x00001C71, 0xD2000000, 0x00000000,
            0x520B21E8, 0xE59F00E8, 0x020B227C, 0xE3A02B07,
            0xD2000000, 0x00000000, 0x5211530C, 0xE59F205C,
            0x02115370, 0x00001C71, 0xD2000000, 0x00000000,
        }
    );

    const std::initializer_list<std::pair<u32, std::size_t>> payloadSizes = {
        {0, 0}, {1, 2}, {7, 2}, {8, 2}, {9, 4}, {15, 4}, {16, 4},
    };
    for (const auto [bytes, words] : payloadSizes)
        Check(melonDS::ARCodePayloadWordCount(bytes) == words, "wrong E payload consumption");
    Check(
        melonDS::ARCodePayloadWordCount(0xFFFFFFFF) == 1073741824,
        "maximum E payload length overflowed"
    );

    ExpectStructure({}, ARCodeValidationError::Empty);
    ExpectStructure({0x00000000, 0x00000000, 0x00000000}, ARCodeValidationError::OddWordCount);
    ExpectStructure({0x00000000, 0x00000000}, ARCodeValidationError::None);
    ExpectStructure({0xE2000000, 0x00000000}, ARCodeValidationError::None);
    ExpectStructure(
        {0xE2000000, 0x00000001, 0x12345678, 0x9ABCDEF0},
        ARCodeValidationError::None
    );
    ExpectStructure(
        {0xE2000000, 0x00000008, 0x12345678, 0x9ABCDEF0},
        ARCodeValidationError::None
    );
    ExpectStructure(
        {0xE2000000, 0x00000009, 0x12345678, 0x9ABCDEF0, 0x11223344, 0x55667788},
        ARCodeValidationError::None
    );

    const auto truncated = melonDS::ValidateARCode(
        {0xE2000000, 0x00000009, 0x12345678, 0x9ABCDEF0}
    );
    Check(truncated.Error == ARCodeValidationError::TruncatedEPayload, "b=9 truncation accepted");
    Check(truncated.InstructionIndex == 1, "wrong truncated E instruction index");
    Check(truncated.PayloadBytes == 9, "wrong truncated E requested byte count");
    Check(truncated.AvailablePayloadBytes == 8, "wrong truncated E available byte count");
    ExpectStructure(
        {0xE2000000, 0x00000010, 0x12345678, 0x9ABCDEF0},
        ARCodeValidationError::TruncatedEPayload
    );
    ExpectStructure(
        {0xE2000000, 0xFFFFFFFF},
        ARCodeValidationError::TruncatedEPayload
    );

    std::cout << "ActionReplayHardeningTest PASS (" << checks << " checks)\n";
    return 0;
}
