import argparse
import json
import os
import re

import markdown2
from fpdf.enums import XPos, YPos, Align
from fpdf.fpdf import FPDF

company_prefix = 'COMPANY '
topic_prefix = 'TOPIC '
executive_summary_prefix = 'EXECUTIVE SUMMARY '
arr_limit = 100000
high_activity_count = 8


class PDF(FPDF):
    def header(self):
        self.set_font('Roboto', '', 12)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, 'AI of Sauron', 0, align='C', new_x=XPos.LMARGIN, new_y=YPos.NEXT)

    def footer(self):
        self.set_y(-15)
        self.set_font('Roboto', '', 8)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, f'Page {self.page_no()}', 0, align='C', new_x=XPos.RIGHT, new_y=YPos.TOP)

    def chapter_title(self, title):
        self.set_font('Roboto', 'B', 24)
        self.set_text_color(0, 0, 0)
        self.cell(0, 10, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_title(self, title):
        self.set_font('Roboto', 'B', 48)
        self.set_text_color(255, 255, 0)
        self.cell(0, 10, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_subtitle(self, title):
        self.set_font('Roboto', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 15, title, 0, align='L', new_x=XPos.LMARGIN, new_y=YPos.NEXT)
        self.ln(10)

    def frontage_dates(self, title):
        self.set_font('Roboto', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 0, title, 0, align='L', new_x=XPos.RIGHT, new_y=YPos.TOP)
        self.ln(10)

    def frontage_slack(self):
        self.set_font('Roboto', 'B', 32)
        self.set_text_color(255, 255, 0)
        self.cell(0, 0, "#topic-solutions-engineering", 0, align='L', new_x=XPos.RIGHT, new_y=YPos.TOP)
        self.ln(10)

    def chapter_body(self, body):
        self.set_font('Roboto', '', 12)
        self.set_text_color(0, 0, 0)
        self.write_html(body, ul_bullet_char="•", li_prefix_color=(0, 0, 0))
        self.ln()

    def add_legend_item(self, image_path, text, x=50):
        """Add a legend item with an image and text"""
        self.image(image_path, x=x, y=self.y, w=6, h=6)
        self.cell(0, 10, text, 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.C)

    def add_icon_if_threshold_met(self, script_dir, icon_value, threshold, icon_name, x_position):
        """Add an icon if the value meets or exceeds the threshold"""
        if icon_value >= threshold:
            self.image(os.path.join(script_dir, f"images/{icon_name}.png"), x=x_position, y=self.y, w=6, h=6)

    def load_fonts(self, script_dir):
        """Load fonts for the PDF"""
        font_dir = os.path.join(script_dir, 'fonts', 'roboto')
        self.add_font('Roboto', '', os.path.join(font_dir, 'Roboto-Regular.ttf'))
        self.add_font('Roboto', 'B', os.path.join(font_dir, 'Roboto-Bold.ttf'))
        self.add_font('Roboto', 'I', os.path.join(font_dir, 'Roboto-Italic.ttf'))
        self.add_font('Roboto', 'BI', os.path.join(font_dir, 'Roboto-BoldItalic.ttf'))


def extract_metadata_value(json_data, name, default=0):
    """Extract metadata value from JSON data based on field name"""
    metadata_fields = [field for field in json_data if field.get("name", "") == name]
    return metadata_fields[0].get("value", default) if len(metadata_fields) > 0 else default


def add_front_page(pdf, title, date_from, date_to, script_dir, cover_page):
    pdf.add_page()
    pdf.image(os.path.join(script_dir, cover_page), x=0, y=0, w=pdf.w, h=pdf.h)
    pdf.frontage_title('AI of Sauron')
    pdf.frontage_subtitle(title)

    pdf.frontage_dates(f'{date_from} to {date_to}')
    pdf.frontage_slack()


def add_instructions_page(pdf, title):
    pdf.add_page()
    pdf.chapter_title("Introduction")
    pdf.chapter_body(f"""
    <p>Welcome to the AI of Sauron {title}.</p>
    <p>This document is an AI generated summary the interactions between Octopus and our customers.</p>
    <p>Because it is AI generated, mistakes may occur. Please verify the information before taking any action.</p>
    """)


def find_average_context(directory, company_prefix):
    print("Parsing metadata...")
    total_context = 0
    total_entities = 0
    average_context = 0
    for filename in sorted(os.listdir(directory)):
        if filename.startswith(company_prefix) and filename.endswith('.json'):
            filepath = os.path.join(directory, filename)
            with open(filepath, 'r', encoding='utf-8') as file:
                try:
                    json_data = json.load(file)
                    count = extract_metadata_value(json_data, "ContextCount")
                    if count > 0:
                        total_entities += 1
                        total_context += count
                except json.JSONDecodeError:
                    print(f"Error parsing {filename}")

    if total_entities > 0:
        average_context = total_context / total_entities

    return average_context


def get_common_themes(pdf, directory):
    if os.path.exists(os.path.join(directory, 'Topics.md')):
        return [{'title': 'Common Themes', 'filename': os.path.join(directory, 'Topics.md'),
                 'link': pdf.add_link(), 'type': 'summary'}]

    return []


def get_topics(pdf, directory, topic_prefix):
    def get_title(filename):
        # Get file name without extension or the prefix
        title = (os.path.splitext(filename)[0])[len(topic_prefix):]
        return title

    return [{'title': get_title(filename), 'filename': filename, 'link': pdf.add_link(), 'type': 'topic'} for filename
            in
            sorted(os.listdir(directory)) if filename.startswith(topic_prefix) and filename.endswith('.md')]


def parse_int(value, default=0):
    """Parse an integer from a string, returning 0 if parsing fails."""
    try:
        return int(value)
    except ValueError:
        return default


def get_companies(pdf, directory, company_prefix, executive_summary_prefix, average_context):
    print("Parsing companies...")
    contents = []
    for filename in sorted(os.listdir(directory)):
        if filename.startswith(company_prefix) and filename.endswith('.md'):
            # Get the filename with extension
            raw_file = os.path.splitext(filename)[0]

            # Get the metadata file
            metadata = os.path.join(directory, raw_file + ".json")

            high_activity = False
            sentiment = 5
            aws = 0
            azure = 0
            costs = 0
            k8s = 0
            github = 0
            migration = 0
            terraform = 0
            performance = 0
            security = 0
            arr = 0
            windows = 0
            linux = 0
            tenants = 0

            if os.path.exists(metadata):
                print(f"Parsing {metadata}...")
                with open(metadata, 'r', encoding='utf-8') as file:
                    try:
                        json_data = json.load(file)
                        # Flag those with above average touch points, but at least 6 if the average is less than 6
                        # Note that we expect all companies to have at least 3 touch points including things
                        # like deployment, project, and tenant stats
                        count = extract_metadata_value(json_data, "ContextCount")
                        if count >= max(average_context, high_activity_count):
                            high_activity = True

                        sentiment = extract_metadata_value(json_data, "Sentiment", 5)
                        aws = extract_metadata_value(json_data, "AWS", 0)
                        azure = extract_metadata_value(json_data, "Azure", 0)
                        costs = extract_metadata_value(json_data, "Costs", 0)
                        k8s = extract_metadata_value(json_data, "Kubernetes", 0)
                        github = extract_metadata_value(json_data, "Github", 0)
                        migration = extract_metadata_value(json_data, "Migration", 0)
                        terraform = extract_metadata_value(json_data, "Terraform", 0)
                        performance = extract_metadata_value(json_data, "Performance", 0)
                        security = extract_metadata_value(json_data, "Security", 0)
                        windows = extract_metadata_value(json_data, "Windows", 0)
                        linux = extract_metadata_value(json_data, "Linux", 0)
                        tenants = extract_metadata_value(json_data, "Tenants", 0)
                        argocd = extract_metadata_value(json_data, "ArgoCD", 0)
                        ai = extract_metadata_value(json_data, "AI", 0)
                        arr = parse_int(extract_metadata_value(json_data, "ARR (SFDC)"))
                        arr2 = parse_int(extract_metadata_value(json_data, "ARR Amount"))
                    except:
                        pass

            # Get file name without extension or the prefix
            title = raw_file[len(company_prefix):]

            executive_summary = filename.replace(company_prefix, executive_summary_prefix)
            executive_summary_file = os.path.join(directory, executive_summary)
            executive_summary_exists = os.path.exists(executive_summary_file)

            print(f'{executive_summary_file} exists: {executive_summary_exists}')

            contents.append(
                {'title': title,
                 'filename': filename,
                 'executive_summary': executive_summary if executive_summary_exists else None,
                 'link': pdf.add_link(),
                 'high_activity': high_activity or arr >= arr_limit or arr2 >= arr_limit,
                 'type': 'customer',
                 'sentiment': sentiment,
                 'aws': aws,
                 'azure': azure,
                 'costs': costs,
                 'k8s': k8s,
                 'github': github,
                 'migration': migration,
                 'terraform': terraform,
                 'performance': performance,
                 'security': security,
                 'windows': windows,
                 'linux': linux,
                 'tenants': tenants,
                 'argocd': argocd,
                 'ai': ai,
                 'arr': arr,
                 'arr2': arr2})

    return contents


def add_toc_categories(pdf, script_dir):
    pdf.add_legend_item(os.path.join(script_dir, "images/smile.png"), 'Sentiment')
    pdf.add_legend_item(os.path.join(script_dir, "images/aws.png"), 'AWS')
    pdf.add_legend_item(os.path.join(script_dir, "images/azure.png"), 'Azure')
    pdf.add_legend_item(os.path.join(script_dir, "images/costs.png"), 'Costs/Licensing')
    pdf.add_legend_item(os.path.join(script_dir, "images/k8s.png"), 'Kubernetes')
    pdf.add_legend_item(os.path.join(script_dir, "images/github.png"), 'GitHub')
    pdf.add_legend_item(os.path.join(script_dir, "images/migration.png"), 'Migration/Upgrade')
    pdf.add_legend_item(os.path.join(script_dir, "images/terraform.png"), 'Terraform')
    pdf.add_legend_item(os.path.join(script_dir, "images/performance.png"), 'Performance')
    pdf.add_legend_item(os.path.join(script_dir, "images/security.png"), 'Security/Compliance')
    pdf.add_legend_item(os.path.join(script_dir, "images/linux.png"), 'Linux')
    pdf.add_legend_item(os.path.join(script_dir, "images/windows.png"), 'Windows')
    pdf.add_legend_item(os.path.join(script_dir, "images/tenants.png"), 'Tenants')
    pdf.add_legend_item(os.path.join(script_dir, "images/argocd.png"), 'ArgoCD')
    pdf.add_legend_item(os.path.join(script_dir, "images/ai.png"), 'AI')


def add_toc(pdf, script_dir, contents, companies):
    high_activity_link = None
    low_activity_link = None

    pdf.add_page()
    pdf.set_text_color(0, 0, 0)
    pdf.set_font('Roboto', 'B', 16)
    pdf.cell(0, 10, 'Table of Contents', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.C)
    pdf.ln(10)
    pdf.set_font('Roboto', '', 12)
    pdf.ln(10)

    # Add legend items using the new helper function
    if len(companies) != 0:
        add_toc_categories(pdf, script_dir)

    pdf.ln(20)

    toc_link = pdf.add_link(y=pdf.y)

    for content in [c for c in contents if c.get('type', '') == 'topic']:
        pdf.cell(0, 10, f'{content['title']}', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L,
                 link=content['link'])

    for content in [c for c in contents if c.get('type', '') == 'summary']:
        pdf.cell(0, 10, f'{content['title']}', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L,
                 link=content['link'])

    if len(companies) != 0:
        high_activity_customers = [c for c in contents if
                                   c.get('high_activity', False) and c.get('type',
                                                                           '') == 'customer']
        low_activity_customers = [c for c in contents if
                                  not c.get('high_activity', False) and c.get('type', '') == 'customer']

        def add_icons(content):
            if content['sentiment'] >= 8:
                pdf.image(os.path.join(script_dir, "images/smile.png"), x=92, y=pdf.y, w=6, h=6)
            elif content['sentiment'] <= 2:
                pdf.image(os.path.join(script_dir, "images/cry.png"), x=92, y=pdf.y, w=6, h=6)

            # Use the new function for each icon
            threshold = 8
            pdf.add_icon_if_threshold_met(script_dir, content['argocd'], threshold, "argocd", 100)
            pdf.add_icon_if_threshold_met(script_dir, content['aws'], threshold, "aws", 106)
            pdf.add_icon_if_threshold_met(script_dir, content['azure'], threshold, "azure", 112)
            pdf.add_icon_if_threshold_met(script_dir, content['costs'], threshold, "costs", 118)
            pdf.add_icon_if_threshold_met(script_dir, content['k8s'], threshold, "k8s", 124)
            pdf.add_icon_if_threshold_met(script_dir, content['github'], threshold, "github", 130)
            pdf.add_icon_if_threshold_met(script_dir, content['migration'], threshold, "migration", 136)
            pdf.add_icon_if_threshold_met(script_dir, content['terraform'], threshold, "terraform", 142)
            pdf.add_icon_if_threshold_met(script_dir, content['performance'], threshold, "performance", 148)
            pdf.add_icon_if_threshold_met(script_dir, content['security'], threshold, "security", 154)
            pdf.add_icon_if_threshold_met(script_dir, content['tenants'], threshold, "tenants", 160)
            pdf.add_icon_if_threshold_met(script_dir, content['windows'], threshold, "windows", 166)
            pdf.add_icon_if_threshold_met(script_dir, content['linux'], threshold, "linux", 172)
            pdf.add_icon_if_threshold_met(script_dir, content['ai'], threshold, "ai", 178)

        if len(high_activity_customers) != 0:
            high_activity_link = pdf.add_link()
            pdf.cell(0, 10, 'High Volume/ARR Customers Executive Summary', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT,
                     align=Align.L, link=high_activity_link)

        if len(low_activity_customers) != 0:
            low_activity_link = pdf.add_link()
            pdf.cell(0, 10, 'Low Volume Customers Executive Summary', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT,
                     align=Align.L, link=low_activity_link)

        if len(high_activity_customers) != 0:
            pdf.set_text_color(58, 0, 0)
            pdf.cell(0, 10, 'High Volume/ARR Customers', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L)
            pdf.set_text_color(0, 0, 0)

            for content in high_activity_customers:
                add_icons(content)
                pdf.cell(0, 10, f'  {content['title']}', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L,
                         link=content['link'])

            pdf.ln(10)

        if len(low_activity_customers) != 0:
            pdf.set_text_color(58, 0, 0)
            pdf.cell(0, 10, 'Low Volume Customers', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L)
            pdf.set_text_color(0, 0, 0)

            for content in low_activity_customers:
                add_icons(content)
                pdf.cell(0, 10, f'  {content['title']}', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L,
                         link=content['link'])

            pdf.ln(10)

    return toc_link, high_activity_link, low_activity_link


def sanitize_html(html_content):
    # The generated HTML will often have nested lists with structures like this
    # <li><p><strong>License Management:</strong></p>
    # <ul>
    # <li>whatever</li>
    # </ul></li>
    # This doesn't render well, so we need to get rid of the paragraph tag
    html_content = re.sub(r'<li><p>(.*?)</p>', r'<li>\1', html_content)
    html_content = re.sub(r'<li><p>(.*?)</p></li>', r'<li>\1</li>', html_content, flags=re.DOTALL)
    return html_content


def add_executive_summaries(pdf, companies, directory, toc_link, high_activity_link, low_activity_link):
    print("Adding executive summaries...")

    for executive_summary_types in [
        {'title': 'High Volume/ARR Customers Executive Summary', 'high_activity': True, 'link': high_activity_link},
        {'title': 'Low Volume Customers Executive Summary', 'high_activity': False, 'link': low_activity_link}]:

        customers = [company for company in companies if
                     company.get('high_activity', False) == executive_summary_types['high_activity'] and company.get(
                         'type', '') == 'customer' and company.get('executive_summary')]

        if len(customers) != 0:
            pdf.add_page()
            pdf.set_link(executive_summary_types['link'])
            pdf.chapter_title(executive_summary_types['title'])

            for company in customers:
                filepath = os.path.join(directory, company['executive_summary'])
                with open(filepath, 'r', encoding='utf-8') as file:
                    md_content = file.read()
                    html_content = markdown2.markdown(md_content)
                    html_content = sanitize_html(html_content)
                    pdf.set_font('Roboto', '', 12)
                    pdf.set_text_color(0, 96, 223)
                    pdf.cell(0, 10, 'Back to TOC', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L, link=toc_link)
                    pdf.set_text_color(0, 0, 0)
                    pdf.chapter_body(html_content)
                    pdf.ln(10)


def add_pages(pdf, contents, directory, toc_link):
    print("Converting markdown...")
    for content in contents:
        filepath = os.path.join(directory, content['filename'])
        with open(filepath, 'r', encoding='utf-8') as file:
            pdf.add_page()
            pdf.set_link(content['link'])
            md_content = file.read()
            html_content = markdown2.markdown(md_content)

            html_content = sanitize_html(html_content)

            pdf.set_font('Roboto', '', 12)
            pdf.set_text_color(0, 96, 223)
            pdf.cell(0, 10, 'Back to TOC', 0, new_x=XPos.LMARGIN, new_y=YPos.NEXT, align=Align.L, link=toc_link)
            pdf.set_text_color(0, 0, 0)
            pdf.chapter_title(content['title'])
            pdf.chapter_body(html_content)


def convert_md_to_pdf(directory, output_pdf, title, date_from, date_to, cover_page):
    print(f"Converting {directory} to {output_pdf}...")

    # Get the directory of the current script
    script_dir = os.path.dirname(os.path.abspath(__file__))

    pdf = PDF()
    pdf.load_fonts(script_dir)

    add_front_page(pdf, title, date_from, date_to, script_dir, cover_page)
    add_instructions_page(pdf, title)

    # Parse the metadata
    average_context = find_average_context(directory, company_prefix)

    companies = get_companies(pdf, directory, company_prefix, executive_summary_prefix, average_context)

    contents = [
        *get_common_themes(pdf, directory),
        *get_topics(pdf, directory, topic_prefix),
        *companies
    ]

    toc_link, high_activity_link, low_activity_link = add_toc(pdf, script_dir, contents, companies)

    add_executive_summaries(pdf, companies, directory, toc_link, high_activity_link, low_activity_link)

    add_pages(pdf, contents, directory, toc_link)

    pdf.output(output_pdf)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Convert Markdown files to PDF.')
    parser.add_argument('--directory', type=str, help='The directory containing Markdown files.')
    parser.add_argument('--pdf', type=str, help='The output PDF file path.')
    parser.add_argument('--title', type=str, help='The PDF title.')
    parser.add_argument('--date_from', type=str, help='The start of the date range.')
    parser.add_argument('--date_to', type=str, help='The end of the date range.')
    parser.add_argument('--cover_page', type=str, help='The cover page image.')

    args = parser.parse_args()

    if not args.directory:
        raise ValueError("The directory argument is required.")

    if not args.pdf:
        raise ValueError("The pdf argument is required.")

    if not args.title:
        raise ValueError("The title argument is required.")

    if not args.date_from:
        raise ValueError("The date_from argument is required.")

    if not args.date_to:
        raise ValueError("The date_to argument is required.")

    if not args.cover_page:
        raise ValueError("The cover_page argument is required.")

    convert_md_to_pdf(args.directory, args.pdf, args.title, args.date_from, args.date_to, args.cover_page)
