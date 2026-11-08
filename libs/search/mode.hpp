#pragma once

#include <string>

namespace search
{
enum class Mode
{
  Everywhere,
  Viewport,
  Downloader,
  Bookmarks,
  // Along a route: in SearchParams::m_routeCorridor, ordered by the distance along the route.
  Route,
  Count
};

std::string DebugPrint(Mode mode);
}  // namespace search
