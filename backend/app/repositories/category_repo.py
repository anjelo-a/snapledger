from app.core.category_seeds import DEFAULT_CATEGORY_SEEDS


def list_seed_categories() -> list[dict[str, object]]:
    return [
        {
            "id": f"seed-{idx}",
            "name": name,
            "is_default": True,
            "is_archived": False,
        }
        for idx, name in enumerate(DEFAULT_CATEGORY_SEEDS, start=1)
    ]
