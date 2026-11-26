use iced::overlay::menu;
use iced::theme::Palette;
use iced::widget::{button, container, pick_list, text_input, toggler};
use iced::{Background, Border, Color, Theme};

// Light Material 3 Expressive Monochrome palette
pub const BG_CANVAS: Color = Color::from_rgb(0.957, 0.961, 0.969);
pub const BG_CARD: Color = Color::WHITE;
pub const BG_SUBTLE: Color = Color::from_rgb(0.925, 0.929, 0.945);
pub const BG_HOVER: Color = Color::from_rgb(0.886, 0.894, 0.918);
pub const BG_PRESSED: Color = Color::from_rgb(0.839, 0.851, 0.882);
pub const BORDER_HAIRLINE: Color = Color::from_rgb(0.886, 0.902, 0.929);
pub const TEXT_PRIMARY: Color = Color::from_rgb(0.067, 0.075, 0.094);
pub const TEXT_MUTED: Color = Color::from_rgb(0.392, 0.420, 0.471);
pub const ACCENT_BLACK: Color = Color::from_rgb(0.094, 0.102, 0.125);
pub const STATUS_GREEN: Color = Color::from_rgb(0.082, 0.502, 0.239);
pub const STATUS_GREEN_BG: Color = Color::from_rgb(0.902, 0.965, 0.925);
pub const STATUS_WARNING: Color = Color::from_rgb(0.753, 0.412, 0.020);
pub const STATUS_WARNING_BG: Color = Color::from_rgb(0.992, 0.945, 0.867);
pub const STATUS_DANGER: Color = Color::from_rgb(0.820, 0.153, 0.153);

// Dark Material 3 Expressive Monochrome palette
pub const DARK_BG_CANVAS: Color = Color::from_rgb(0.043, 0.047, 0.059);
pub const DARK_BG_CARD: Color = Color::from_rgb(0.082, 0.090, 0.114);
pub const DARK_BG_SUBTLE: Color = Color::from_rgb(0.125, 0.137, 0.169);
pub const DARK_BG_HOVER: Color = Color::from_rgb(0.157, 0.173, 0.212);
pub const DARK_BG_PRESSED: Color = Color::from_rgb(0.188, 0.208, 0.259);
pub const DARK_TEXT_PRIMARY: Color = Color::from_rgb(0.953, 0.957, 0.965);
pub const DARK_TEXT_MUTED: Color = Color::from_rgb(0.612, 0.639, 0.686);
pub const DARK_ACCENT_PRIMARY: Color = Color::from_rgb(0.945, 0.953, 0.965);
pub const DARK_STATUS_GREEN: Color = Color::from_rgb(0.133, 0.773, 0.369);
pub const DARK_STATUS_GREEN_BG: Color = Color::from_rgb(0.075, 0.149, 0.106);
pub const DARK_STATUS_WARNING: Color = Color::from_rgb(0.984, 0.749, 0.141);
pub const DARK_STATUS_WARNING_BG: Color = Color::from_rgb(0.180, 0.141, 0.067);
pub const DARK_STATUS_DANGER: Color = Color::from_rgb(0.973, 0.443, 0.443);

pub fn is_dark_theme(theme: &Theme) -> bool {
    theme.palette().background.r < 0.5
}

pub fn primary_color(dark: bool) -> Color {
    if dark {
        DARK_TEXT_PRIMARY
    } else {
        TEXT_PRIMARY
    }
}

pub fn muted_color(dark: bool) -> Color {
    if dark {
        DARK_TEXT_MUTED
    } else {
        TEXT_MUTED
    }
}

pub fn success_color(dark: bool) -> Color {
    if dark {
        DARK_STATUS_GREEN
    } else {
        STATUS_GREEN
    }
}

pub fn warning_color(dark: bool) -> Color {
    if dark {
        DARK_STATUS_WARNING
    } else {
        STATUS_WARNING
    }
}

pub fn monochrome_theme(dark: bool) -> Theme {
    if dark {
        Theme::custom(
            "SilentInput Monochrome Dark",
            Palette {
                background: DARK_BG_CANVAS,
                text: DARK_TEXT_PRIMARY,
                primary: DARK_ACCENT_PRIMARY,
                success: DARK_STATUS_GREEN,
                warning: DARK_STATUS_WARNING,
                danger: DARK_STATUS_DANGER,
            },
        )
    } else {
        Theme::custom(
            "SilentInput Monochrome Light",
            Palette {
                background: BG_CANVAS,
                text: TEXT_PRIMARY,
                primary: ACCENT_BLACK,
                success: STATUS_GREEN,
                warning: STATUS_WARNING,
                danger: STATUS_DANGER,
            },
        )
    }
}

pub fn card_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark { DARK_BG_CARD } else { BG_CARD })),
        text_color: Some(primary_color(dark)),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 20.0.into(),
        },
        // Keep Shadow disabled: iced_tiny_skia draws quad shadows without clip_mask,
        // causing unclipped shadow alpha to accumulate across partial damage frames.
        ..Default::default()
    }
}

pub fn hero_card_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark { DARK_BG_CARD } else { BG_CARD })),
        text_color: Some(primary_color(dark)),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 24.0.into(),
        },
        ..Default::default()
    }
}

pub fn qr_paper_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(Color::WHITE)),
        border: Border {
            color: if dark {
                Color::TRANSPARENT
            } else {
                BORDER_HAIRLINE
            },
            width: if dark { 0.0 } else { 1.0 },
            radius: 18.0.into(),
        },
        ..Default::default()
    }
}

pub fn qr_dark_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgb(0.05, 0.06, 0.08))),
        ..Default::default()
    }
}

pub fn qr_light_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::WHITE)),
        ..Default::default()
    }
}

pub fn subtle_box_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark {
            DARK_BG_SUBTLE
        } else {
            BG_SUBTLE
        })),
        text_color: Some(primary_color(dark)),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 14.0.into(),
        },
        ..Default::default()
    }
}

pub fn status_pill_style(running: bool) -> impl Fn(&Theme) -> container::Style {
    move |theme: &Theme| {
        let dark = is_dark_theme(theme);
        let (bg, text_color) = if running {
            if dark {
                (DARK_STATUS_GREEN_BG, DARK_STATUS_GREEN)
            } else {
                (STATUS_GREEN_BG, STATUS_GREEN)
            }
        } else if dark {
            (DARK_BG_SUBTLE, DARK_TEXT_MUTED)
        } else {
            (BG_SUBTLE, TEXT_MUTED)
        };
        container::Style {
            background: Some(Background::Color(bg)),
            text_color: Some(text_color),
            border: Border {
                color: Color::TRANSPARENT,
                width: 0.0,
                radius: 999.0.into(),
            },
            ..Default::default()
        }
    }
}

pub fn toast_style(theme: &Theme) -> container::Style {
    let dark = is_dark_theme(theme);
    container::Style {
        background: Some(Background::Color(if dark {
            DARK_BG_SUBTLE
        } else {
            ACCENT_BLACK
        })),
        text_color: Some(Color::WHITE),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 999.0.into(),
        },
        ..Default::default()
    }
}

pub fn modal_backdrop_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgba(0.0, 0.0, 0.0, 0.55))),
        ..Default::default()
    }
}

pub fn tonal_button_style(radius: f32) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |theme: &Theme, status: button::Status| {
        let dark = is_dark_theme(theme);
        let (bg, text) = if dark {
            let bg = match status {
                button::Status::Hovered => DARK_BG_HOVER,
                button::Status::Pressed => DARK_BG_PRESSED,
                button::Status::Disabled => DARK_BG_CARD,
                button::Status::Active => DARK_BG_SUBTLE,
            };
            let text = if matches!(status, button::Status::Disabled) {
                DARK_TEXT_MUTED
            } else {
                DARK_TEXT_PRIMARY
            };
            (bg, text)
        } else {
            let bg = match status {
                button::Status::Hovered => BG_HOVER,
                button::Status::Pressed => BG_PRESSED,
                button::Status::Disabled => BG_SUBTLE,
                button::Status::Active => BG_SUBTLE,
            };
            let text = if matches!(status, button::Status::Disabled) {
                TEXT_MUTED
            } else {
                TEXT_PRIMARY
            };
            (bg, text)
        };
        button::Style {
            background: Some(Background::Color(bg)),
            text_color: text,
            border: Border {
                color: Color::TRANSPARENT,
                width: 0.0,
                radius: radius.into(),
            },
            ..Default::default()
        }
    }
}

pub fn pill_button_style(theme: &Theme, status: button::Status) -> button::Style {
    tonal_button_style(999.0)(theme, status)
}

pub fn secondary_button_style(theme: &Theme, status: button::Status) -> button::Style {
    tonal_button_style(12.0)(theme, status)
}

pub fn warning_pill_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let bg = if dark {
        match status {
            button::Status::Hovered => Color::from_rgb(0.220, 0.173, 0.082),
            button::Status::Pressed => Color::from_rgb(0.259, 0.204, 0.098),
            _ => DARK_STATUS_WARNING_BG,
        }
    } else {
        match status {
            button::Status::Hovered => Color::from_rgb(0.980, 0.918, 0.808),
            button::Status::Pressed => Color::from_rgb(0.965, 0.886, 0.749),
            _ => STATUS_WARNING_BG,
        }
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color: warning_color(dark),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 999.0.into(),
        },
        ..Default::default()
    }
}

pub fn ghost_row_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let bg = match status {
        button::Status::Hovered => Some(Background::Color(if dark {
            Color::from_rgba(1.0, 1.0, 1.0, 0.03)
        } else {
            Color::from_rgba(0.0, 0.0, 0.0, 0.03)
        })),
        button::Status::Pressed => Some(Background::Color(if dark {
            Color::from_rgba(1.0, 1.0, 1.0, 0.06)
        } else {
            Color::from_rgba(0.0, 0.0, 0.0, 0.06)
        })),
        _ => None,
    };
    button::Style {
        background: bg,
        text_color: primary_color(dark),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 14.0.into(),
        },
        ..Default::default()
    }
}

pub fn primary_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let (bg, text_color) = if dark {
        let bg = match status {
            button::Status::Hovered => Color::WHITE,
            button::Status::Pressed => Color::from_rgb(0.835, 0.851, 0.875),
            button::Status::Disabled => DARK_BG_SUBTLE,
            button::Status::Active => DARK_ACCENT_PRIMARY,
        };
        let text = if matches!(status, button::Status::Disabled) {
            DARK_TEXT_MUTED
        } else {
            DARK_BG_CANVAS
        };
        (bg, text)
    } else {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.161, 0.176, 0.212),
            button::Status::Pressed => Color::from_rgb(0.043, 0.047, 0.059),
            button::Status::Disabled => Color::from_rgb(0.612, 0.639, 0.686),
            button::Status::Active => ACCENT_BLACK,
        };
        (bg, Color::WHITE)
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color,
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 999.0.into(),
        },
        ..Default::default()
    }
}

pub fn danger_button_style(theme: &Theme, status: button::Status) -> button::Style {
    let dark = is_dark_theme(theme);
    let (bg, text_color) = if dark {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.247, 0.110, 0.122),
            button::Status::Pressed => Color::from_rgb(0.310, 0.133, 0.149),
            button::Status::Disabled => DARK_BG_SUBTLE,
            button::Status::Active => Color::from_rgb(0.184, 0.086, 0.094),
        };
        (bg, DARK_STATUS_DANGER)
    } else {
        let bg = match status {
            button::Status::Hovered => Color::from_rgb(0.992, 0.871, 0.871),
            button::Status::Pressed => Color::from_rgb(0.984, 0.792, 0.792),
            button::Status::Disabled => BG_SUBTLE,
            button::Status::Active => Color::from_rgb(0.992, 0.925, 0.925),
        };
        (bg, STATUS_DANGER)
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color,
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 999.0.into(),
        },
        ..Default::default()
    }
}

pub fn input_style(theme: &Theme, status: text_input::Status) -> text_input::Style {
    let dark = is_dark_theme(theme);
    let (border_color, border_width) = match status {
        text_input::Status::Focused { .. } => (
            if dark {
                DARK_ACCENT_PRIMARY
            } else {
                ACCENT_BLACK
            },
            1.5,
        ),
        _ => (Color::TRANSPARENT, 0.0),
    };
    text_input::Style {
        background: Background::Color(if dark { DARK_BG_SUBTLE } else { BG_SUBTLE }),
        border: Border {
            color: border_color,
            width: border_width,
            radius: 12.0.into(),
        },
        icon: muted_color(dark),
        placeholder: muted_color(dark),
        value: primary_color(dark),
        selection: Color::from_rgba(0.5, 0.5, 0.5, 0.3),
    }
}

pub fn toggler_style(theme: &Theme, status: toggler::Status) -> toggler::Style {
    let dark = is_dark_theme(theme);
    let (track, thumb) = match status {
        toggler::Status::Active { is_toggled } | toggler::Status::Hovered { is_toggled } => {
            let hovered = matches!(status, toggler::Status::Hovered { .. });
            if is_toggled {
                if dark {
                    (
                        if hovered {
                            Color::WHITE
                        } else {
                            DARK_ACCENT_PRIMARY
                        },
                        DARK_BG_CARD,
                    )
                } else {
                    (
                        if hovered {
                            Color::from_rgb(0.18, 0.20, 0.24)
                        } else {
                            ACCENT_BLACK
                        },
                        Color::WHITE,
                    )
                }
            } else if dark {
                (
                    if hovered {
                        Color::from_rgb(0.247, 0.267, 0.322)
                    } else {
                        Color::from_rgb(0.204, 0.220, 0.267)
                    },
                    DARK_TEXT_MUTED,
                )
            } else {
                (
                    if hovered {
                        Color::from_rgb(0.78, 0.80, 0.84)
                    } else {
                        Color::from_rgb(0.83, 0.85, 0.88)
                    },
                    Color::WHITE,
                )
            }
        }
        toggler::Status::Disabled { is_toggled } => {
            if is_toggled {
                (muted_color(dark), if dark { DARK_BG_CARD } else { Color::WHITE })
            } else {
                (if dark { DARK_BG_SUBTLE } else { BG_SUBTLE }, muted_color(dark))
            }
        }
    };
    toggler::Style {
        background: Background::Color(track),
        background_border_width: 0.0,
        background_border_color: Color::TRANSPARENT,
        foreground: Background::Color(thumb),
        foreground_border_width: 0.0,
        foreground_border_color: Color::TRANSPARENT,
        text_color: None,
        border_radius: None,
        padding_ratio: 0.14,
    }
}

pub fn pick_list_style(theme: &Theme, status: pick_list::Status) -> pick_list::Style {
    let dark = is_dark_theme(theme);
    let bg = if dark {
        match status {
            pick_list::Status::Hovered | pick_list::Status::Opened { .. } => DARK_BG_HOVER,
            pick_list::Status::Active => DARK_BG_SUBTLE,
        }
    } else {
        match status {
            pick_list::Status::Hovered | pick_list::Status::Opened { .. } => BG_HOVER,
            pick_list::Status::Active => BG_SUBTLE,
        }
    };
    pick_list::Style {
        text_color: primary_color(dark),
        placeholder_color: muted_color(dark),
        handle_color: muted_color(dark),
        background: Background::Color(bg),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 12.0.into(),
        },
    }
}

pub fn pick_list_menu_style(theme: &Theme) -> menu::Style {
    let dark = is_dark_theme(theme);
    menu::Style {
        background: Background::Color(if dark { DARK_BG_SUBTLE } else { BG_CARD }),
        border: Border {
            color: if dark {
                Color::from_rgb(0.20, 0.22, 0.26)
            } else {
                BORDER_HAIRLINE
            },
            width: 1.0,
            radius: 12.0.into(),
        },
        text_color: primary_color(dark),
        selected_text_color: if dark { DARK_BG_CANVAS } else { Color::WHITE },
        selected_background: Background::Color(if dark {
            DARK_ACCENT_PRIMARY
        } else {
            ACCENT_BLACK
        }),
        shadow: Default::default(),
    }
}
