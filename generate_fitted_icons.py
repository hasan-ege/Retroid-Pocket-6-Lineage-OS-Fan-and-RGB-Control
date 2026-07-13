import os
from PIL import Image, ImageOps

src_icon = "icon.png"
res_dir = "app_project/app/src/main/res"

sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

try:
    with Image.open(src_icon) as img:
        img = img.convert("RGBA")
        
        for density, size in sizes.items():
            out_dir = os.path.join(res_dir, f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)
            
            # Use ImageOps.pad to fit the image into the target size, adding transparent padding
            fitted = ImageOps.pad(img, (size, size), method=Image.Resampling.LANCZOS, color=(0,0,0,0))
            
            fitted.save(os.path.join(out_dir, "ic_launcher.png"))
            fitted.save(os.path.join(out_dir, "ic_launcher_round.png"))
            
        # Create settings icon (24dp ~ 72px for xhdpi usually, but let's just make a good resolution one)
        drawable_dir = os.path.join(res_dir, "drawable")
        os.makedirs(drawable_dir, exist_ok=True)
        settings_icon = ImageOps.pad(img, (96, 96), method=Image.Resampling.LANCZOS, color=(0,0,0,0))
        settings_icon.save(os.path.join(drawable_dir, "ic_settings_icon.png"))
        
    print("Fitted icons generated successfully!")
except Exception as e:
    print(f"Error: {e}")
