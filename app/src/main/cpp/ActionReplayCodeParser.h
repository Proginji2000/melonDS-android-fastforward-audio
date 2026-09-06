#ifndef ACTIONREPLAYCODEPARSER_H
#define ACTIONREPLAYCODEPARSER_H

#include <charconv>
#include <cstddef>
#include <string_view>
#include <system_error>
#include <vector>

#include "types.h"

namespace MelonDSAndroid
{

enum class ActionReplayParseError
{
    None,
    Empty,
    InvalidToken,
    ValueOutOfRange,
    OddWordCount,
};

struct ActionReplayParseResult
{
    std::vector<melonDS::u32> Code;
    ActionReplayParseError Error = ActionReplayParseError::None;
    std::size_t TokenIndex = 0;

    [[nodiscard]] bool IsValid() const noexcept
    {
        return Error == ActionReplayParseError::None;
    }
};

inline ActionReplayParseResult ParseActionReplayCode(std::string_view input)
{
    ActionReplayParseResult result;
    const auto isWhitespace = [](char character) noexcept {
        return character == ' ' || character == '\t' || character == '\n' ||
               character == '\r' || character == '\f' || character == '\v';
    };
    std::size_t offset = 0;

    while (offset < input.size())
    {
        while (offset < input.size() && isWhitespace(input[offset]))
            offset++;
        if (offset == input.size())
            break;

        const std::size_t tokenStart = offset;
        while (offset < input.size() && !isWhitespace(input[offset]))
            offset++;
        const std::string_view token = input.substr(tokenStart, offset - tokenStart);

        const std::size_t tokenIndex = result.Code.size();
        melonDS::u32 value = 0;
        const char* begin = token.data();
        const char* end = begin + token.size();
        const auto [parsedEnd, error] = std::from_chars(begin, end, value, 16);

        if (error == std::errc::result_out_of_range)
        {
            result.Error = ActionReplayParseError::ValueOutOfRange;
            result.TokenIndex = tokenIndex;
            return result;
        }
        if (token.size() != 8 || error != std::errc() || parsedEnd != end)
        {
            result.Error = ActionReplayParseError::InvalidToken;
            result.TokenIndex = tokenIndex;
            return result;
        }

        result.Code.push_back(value);
    }

    if (result.Code.empty())
    {
        result.Error = ActionReplayParseError::Empty;
    }
    else if ((result.Code.size() & 1) != 0)
    {
        result.Error = ActionReplayParseError::OddWordCount;
    }

    return result;
}

}

#endif // ACTIONREPLAYCODEPARSER_H
