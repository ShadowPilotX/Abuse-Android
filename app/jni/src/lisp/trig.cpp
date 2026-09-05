#include <cmath>
#include <algorithm>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265359
#endif

// Declare the arrays to match the extern declarations in lisp.h
int32_t sin_table[360];
uint16_t atan_table[1662];

// Initialize the arrays using a function that runs at program startup
namespace
{
    struct TableInitializer
    {
        TableInitializer()
        {
            // Initialize sin_table
            for (int i = 0; i < 360; ++i)
            {
                sin_table[i] = static_cast<int32_t>(std::round(65536 * std::sin(M_PI * i / 180.0)));
            }

            // Initialize atan_table
            for (int i = 0; i < 1662; ++i)
            {
                double angle = std::round(std::atan(i / 29.0) / M_PI * 180.0) - 45.0;
                atan_table[i] = static_cast<uint16_t>(std::max<double>(0.0, angle));
            }
        }
    } initializer;
}