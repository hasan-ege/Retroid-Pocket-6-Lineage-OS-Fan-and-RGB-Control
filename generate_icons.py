import os
from PIL import Image

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
        # Convert to RGBA just in case
        img = img.convert("RGBA")
        
        for density, size in sizes.items():
            out_dir = os.path.join(res_dir, f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)
            
            # Create ic_launcher.png (legacy/fallback)
            resized = img.resize((size, size), Image.Resampling.LANCZOS)
            resized.save(os.path.join(out_dir, "ic_launcher.png"))
            
            # Create ic_launcher_round.png (rounded version)
            # Create a circular mask
            mask = Image.new('L', (size, size), 0)
            from PIL import ImageDraw
            draw = ImageDraw.Draw(mask)
            draw.ellipse((0, 0, size, size), fill=255)
            
            round_img = Image.new('RGBA', (size, size), (0,0,0,0))
            round_img.paste(resized, (0,0), mask)
            round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))
            
    print("Icons generated successfully!")
except Exception as e:
    print(f"Error: {e}")
