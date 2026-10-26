#include "search/displayed_categories.hpp"

#include <algorithm>

namespace search
{
DisplayedCategories::DisplayedCategories(CategoriesHolder const & holder) : m_holder(holder)
{
  // better-maps: single places to eat and drink right after "category_eat" (texts and icons only on Android).
  m_keys = {"category_eat",      "category_restaurant", "category_cafe",          "category_bakery",
            "category_bar_club", "category_hotel",      "category_food",          "category_tourism",
            "category_wifi",     "category_transport",  "category_fuel",          "category_parking",
            "category_shopping", "category_secondhand", "category_atm",           "category_nightlife",
            "category_children", "category_bank",       "category_entertainment", "category_water",
            "category_hospital", "category_pharmacy",   "category_recycling",     "category_rv",
            "category_police",   "category_toilet",     "category_post"};
}

void DisplayedCategories::Modify(CategoriesModifier & modifier)
{
  modifier.Modify(m_keys);
}

std::vector<std::string> const & DisplayedCategories::GetKeys() const
{
  return m_keys;
}
}  // namespace search
